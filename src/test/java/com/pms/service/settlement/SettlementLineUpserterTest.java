package com.pms.service.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.CoupangOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Order;
import com.pms.domain.OrderLine;
import com.pms.domain.Platform;
import com.pms.domain.ProductListingOption;
import com.pms.domain.SaleType;
import com.pms.domain.SettlementLine;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.CoupangOrderLineRepository;
import com.pms.repository.OrderLineRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.SettlementLineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 정산 라인 멱등 upsert — 정정 반영 · 매핑 보존 · UNMATCHED 저장 · 실행 내 중복 감지 (PLAN D7·D10).
 */
@ExtendWith(MockitoExtension.class)
class SettlementLineUpserterTest {

    private static final LocalDate RECOGNITION = LocalDate.of(2026, 8, 5);

    @Mock private SettlementLineRepository settlementLineRepository;
    @Mock private ProductListingOptionRepository productListingOptionRepository;
    @Mock private OrderLineRepository orderLineRepository;
    @Mock private CoupangOrderLineRepository coupangOrderLineRepository;

    private SettlementLineUpserter upserter;

    private final MarketplaceAccount account = MarketplaceAccountFixture.coupangStubBuilder("V1", null)
            .id(7L).platform(Platform.COUPANG).build();

    @BeforeEach
    void setUp() {
        // 미러 기록기는 비워 둔다 — 이 테스트의 관심사는 중립 라인이다(미러 없음도 정상 동작).
        upserter = new SettlementLineUpserter(settlementLineRepository, productListingOptionRepository,
                orderLineRepository, coupangOrderLineRepository, List.of());
        given(settlementLineRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void upsertUpdatesExistingLineInsteadOfInserting() {
        SettlementLine existing = SettlementLine.builder()
                .id(11L).marketplaceAccount(account)
                .externalOrderId("O1").platformOptionId("V10").saleType(SaleType.SALE)
                .recognitionDate(RECOGNITION).saleAmount(new BigDecimal("10000"))
                .build();
        given(settlementLineRepository
                .findByMarketplaceAccount_IdAndExternalOrderIdAndPlatformOptionIdAndSaleTypeAndRecognitionDate(
                        anyLong(), anyString(), anyString(), any(), any()))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(existing));

        upserter.upsertPage(account, List.of(draft("10000")), new HashSet<>());
        upserter.upsertPage(account, List.of(draft("9000")), new HashSet<>());

        ArgumentCaptor<SettlementLine> saved = ArgumentCaptor.forClass(SettlementLine.class);
        verify(settlementLineRepository, times(2)).save(saved.capture());
        // 1회차 = 신규(id 없음) / 2회차 = 같은 행 갱신(id 유지 + 정정 금액)
        assertThat(saved.getAllValues().get(0).getId()).isNull();
        assertThat(saved.getAllValues().get(1).getId()).isEqualTo(11L);
        assertThat(saved.getAllValues().get(1).getSaleAmount()).isEqualByComparingTo("9000");
        verify(settlementLineRepository, never()).delete(any());
    }

    @Test
    void upsertKeepsOrderLineMappingOnCorrection() {
        OrderLine mapped = OrderLine.builder().id(500L).order(Order.builder().id(9L).build()).build();
        ProductListingOption option = ProductListingOption.builder().id(3L).build();
        SettlementLine existing = SettlementLine.builder()
                .id(11L).marketplaceAccount(account).orderLine(mapped).productListingOption(option)
                .externalOrderId("O1").platformOptionId("V10").saleType(SaleType.SALE)
                .recognitionDate(RECOGNITION).saleAmount(new BigDecimal("10000"))
                .build();
        given(settlementLineRepository
                .findByMarketplaceAccount_IdAndExternalOrderIdAndPlatformOptionIdAndSaleTypeAndRecognitionDate(
                        anyLong(), anyString(), anyString(), any(), any()))
                .willReturn(Optional.of(existing));

        SettlementLineUpserter.UpsertResult result =
                upserter.upsertPage(account, List.of(draft("9000")), new HashSet<>());

        ArgumentCaptor<SettlementLine> saved = ArgumentCaptor.forClass(SettlementLine.class);
        verify(settlementLineRepository).save(saved.capture());
        assertThat(saved.getValue().getOrderLine()).isSameAs(mapped);
        assertThat(saved.getValue().getSaleAmount()).isEqualByComparingTo("9000");
        assertThat(result.matched()).isEqualTo(1);
        // 이미 붙은 매핑은 다시 조회하지 않는다(재조회가 멀쩡한 매핑을 밀어내지 않게).
        verify(orderLineRepository, never()).findByExternalOrderIdAndListingOptionId(anyString(), anyLong());
    }

    @Test
    void unmatchedLineIsStoredWithNullOrderLine() {
        given(settlementLineRepository
                .findByMarketplaceAccount_IdAndExternalOrderIdAndPlatformOptionIdAndSaleTypeAndRecognitionDate(
                        anyLong(), anyString(), anyString(), any(), any()))
                .willReturn(Optional.empty());
        given(productListingOptionRepository.findByPlatformOptionId("V10")).willReturn(Optional.empty());

        SettlementLineUpserter.UpsertResult result =
                upserter.upsertPage(account, List.of(draft("10000")), new HashSet<>());

        ArgumentCaptor<SettlementLine> saved = ArgumentCaptor.forClass(SettlementLine.class);
        verify(settlementLineRepository).save(saved.capture());
        assertThat(saved.getValue().getOrderLine()).isNull();     // UNMATCHED = 정상 상태
        assertThat(saved.getValue().getSettlementPayout()).isNull();   // 지급 묶음은 02 가 채운다
        assertThat(result.lines()).isEqualTo(1);
        assertThat(result.unmatched()).isEqualTo(1);
        assertThat(result.matched()).isZero();
    }

    /**
     * 🔴 <b>주문번호 + 마켓 옵션번호로 바로 붙는다</b>(FEATURE_2609_34) — 등록된 채널 옵션이 없어도.
     * 예전에는 등록을 거쳐야만 붙어서 아직 등록하지 않은 상품의 판매가 전부 미분류로 남았다
     * (prod 실측 2.8% → 61.3%).
     */
    @Test
    void matchesOrderDirectlyByOrderAndOptionNumber() {
        OrderLine line = OrderLine.builder().id(500L).build();
        given(settlementLineRepository
                .findByMarketplaceAccount_IdAndExternalOrderIdAndPlatformOptionIdAndSaleTypeAndRecognitionDate(
                        anyLong(), anyString(), anyString(), any(), any()))
                .willReturn(Optional.empty());
        given(coupangOrderLineRepository.findByMarketplaceAccount_IdAndOrderIdRawAndVendorItemId(7L, "O1", "V10"))
                .willReturn(List.of(CoupangOrderLine.builder().orderLine(line).build()));

        SettlementLineUpserter.UpsertResult result =
                upserter.upsertPage(account, List.of(draft("10000")), new HashSet<>());

        ArgumentCaptor<SettlementLine> saved = ArgumentCaptor.forClass(SettlementLine.class);
        verify(settlementLineRepository).save(saved.capture());
        assertThat(saved.getValue().getOrderLine()).isSameAs(line);
        assertThat(result.matched()).isEqualTo(1);
        // 등록 목록을 뒤질 필요가 없다.
        verify(orderLineRepository, never()).findByExternalOrderIdAndListingOptionId(anyString(), anyLong());
    }

    /**
     * 같은 주문·같은 옵션이 여러 박스에 걸쳐 있으면(합포장 분할) <b>하나를 고른다</b> — 어느 쪽이든 상품은
     * 같고 금액은 정산 쪽 값을 쓴다. 버리면 그 판매가 영영 미분류로 남는다.
     */
    @Test
    void splitBoxesPickOneOrderLineInsteadOfGivingUp() {
        given(settlementLineRepository
                .findByMarketplaceAccount_IdAndExternalOrderIdAndPlatformOptionIdAndSaleTypeAndRecognitionDate(
                        anyLong(), anyString(), anyString(), any(), any()))
                .willReturn(Optional.empty());
        given(coupangOrderLineRepository.findByMarketplaceAccount_IdAndOrderIdRawAndVendorItemId(7L, "O1", "V10"))
                .willReturn(List.of(
                        CoupangOrderLine.builder().orderLine(OrderLine.builder().id(9L).build()).build(),
                        CoupangOrderLine.builder().orderLine(OrderLine.builder().id(4L).build()).build()));

        SettlementLineUpserter.UpsertResult result =
                upserter.upsertPage(account, List.of(draft("10000")), new HashSet<>());

        ArgumentCaptor<SettlementLine> saved = ArgumentCaptor.forClass(SettlementLine.class);
        verify(settlementLineRepository).save(saved.capture());
        assertThat(saved.getValue().getOrderLine().getId()).isEqualTo(4L);   // 항상 같은 것이 나온다
        assertThat(result.matched()).isEqualTo(1);
    }

    /** 마켓 주문이 우리 DB 에 없을 때만 옛 경로(등록 채널 옵션 경유)로 내려간다. */
    @Test
    void matchesOrderLineThroughListingOption() {
        ProductListingOption option = ProductListingOption.builder().id(3L).build();
        OrderLine line = OrderLine.builder().id(500L).build();
        given(settlementLineRepository
                .findByMarketplaceAccount_IdAndExternalOrderIdAndPlatformOptionIdAndSaleTypeAndRecognitionDate(
                        anyLong(), anyString(), anyString(), any(), any()))
                .willReturn(Optional.empty());
        given(productListingOptionRepository.findByPlatformOptionId("V10")).willReturn(Optional.of(option));
        given(orderLineRepository.findByExternalOrderIdAndListingOptionId("O1", 3L)).willReturn(List.of(line));

        SettlementLineUpserter.UpsertResult result =
                upserter.upsertPage(account, List.of(draft("10000")), new HashSet<>());

        ArgumentCaptor<SettlementLine> saved = ArgumentCaptor.forClass(SettlementLine.class);
        verify(settlementLineRepository).save(saved.capture());
        assertThat(saved.getValue().getOrderLine()).isSameAs(line);
        assertThat(result.matched()).isEqualTo(1);
    }

    /** 옛 경로에서 후보가 여럿이면 그대로 미분류다 — 그쪽은 옵션이 같다는 보장이 없다. */
    @Test
    void ambiguousOrderLineIsLeftUnmatched() {
        ProductListingOption option = ProductListingOption.builder().id(3L).build();
        given(settlementLineRepository
                .findByMarketplaceAccount_IdAndExternalOrderIdAndPlatformOptionIdAndSaleTypeAndRecognitionDate(
                        anyLong(), anyString(), anyString(), any(), any()))
                .willReturn(Optional.empty());
        given(productListingOptionRepository.findByPlatformOptionId("V10")).willReturn(Optional.of(option));
        given(orderLineRepository.findByExternalOrderIdAndListingOptionId(eq("O1"), eq(3L)))
                .willReturn(List.of(OrderLine.builder().id(1L).build(), OrderLine.builder().id(2L).build()));

        SettlementLineUpserter.UpsertResult result =
                upserter.upsertPage(account, List.of(draft("10000")), new HashSet<>());

        assertThat(result.unmatched()).isEqualTo(1);
    }

    @Test
    void duplicateKeyWithinSameRunIsCounted() {
        given(settlementLineRepository
                .findByMarketplaceAccount_IdAndExternalOrderIdAndPlatformOptionIdAndSaleTypeAndRecognitionDate(
                        anyLong(), anyString(), anyString(), any(), any()))
                .willReturn(Optional.empty());
        Set<String> seen = new HashSet<>();

        SettlementLineUpserter.UpsertResult result =
                upserter.upsertPage(account, List.of(draft("10000"), draft("9000")), seen);

        // 같은 실행에서 같은 유일키가 두 번 = upsert 가 금액을 덮어써 조용히 잃는 상황이다.
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(result.lines()).isEqualTo(2);
    }

    private SettlementLineDraft draft(String saleAmount) {
        return new SettlementLineDraft("O1", "V10", SaleType.SALE, RECOGNITION,
                LocalDate.of(2026, 8, 1), null, null, 2,
                new BigDecimal(saleAmount), new BigDecimal("1060"), new BigDecimal("106"),
                new BigDecimal("10.6"), new BigDecimal("800"), BigDecimal.ZERO,
                new BigDecimal("8834"), new ObjectMapper().createObjectNode());
    }
}
