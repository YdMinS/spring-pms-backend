package com.pms.service.price;

import com.pms.domain.ListingStatus;
import com.pms.domain.PriceChangeLog;
import com.pms.domain.PriceChangeReason;
import com.pms.domain.PriceTargetType;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.repository.PriceChangeLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

/**
 * 가격 변경 이력 기록 규칙 (PLAN 2609_28 D23).
 *
 * <p>🔴 이 클래스가 지키는 것은 "무엇을 남기는가"가 아니라 <b>무엇을 남기지 않는가</b>다 —
 * 최초 설정·DRAFT 셀·값이 같은 저장이 새면 목록이 노이즈로 덮여 변동사가 안 보인다.
 */
@ExtendWith(MockitoExtension.class)
class PriceHistoryRecorderTest {

    @Mock private PriceChangeLogRepository priceChangeLogRepository;

    @InjectMocks private PriceHistoryRecorder recorder;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // --- fixtures ---

    private static Product product() {
        return Product.builder().id(33L).productName("양말A").price(new BigDecimal("3000.00")).build();
    }

    private static ProductListingOption option(ProductListing cell, BigDecimal sellingPrice) {
        return ProductListingOption.builder()
                .id(201L).productListing(cell).optionName("3켤레").sellingPrice(sellingPrice).build();
    }

    private static ProductListing cell(ListingStatus status) {
        return ProductListing.builder().id(101L).name("쿠팡 셀").status(status).build();
    }

    private List<PriceChangeLog> captureSaved() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PriceChangeLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(priceChangeLogRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    // --- 기록 ---

    @Test
    void testRecordsWhenPriceChanged() {
        recorder.recordProductCost(product(), new BigDecimal("3000.00"), new BigDecimal("4000.00"),
                PriceChangeReason.PURCHASE_UPDATE, 77L);

        List<PriceChangeLog> saved = captureSaved();
        assertThat(saved).hasSize(1);
        PriceChangeLog row = saved.get(0);
        assertThat(row.getTargetType()).isEqualTo(PriceTargetType.PRODUCT_COST);
        assertThat(row.getProduct().getId()).isEqualTo(33L);
        assertThat(row.getListingOption()).isNull();
        assertThat(row.getOldPrice()).isEqualByComparingTo("3000");
        assertThat(row.getNewPrice()).isEqualByComparingTo("4000");
        assertThat(row.getReason()).isEqualTo(PriceChangeReason.PURCHASE_UPDATE);
        assertThat(row.getPurchaseRecordId()).isEqualTo(77L);
    }

    @Test
    void testDoesNotRecordWhenPriceUnchanged() {
        recorder.recordProductCost(product(), new BigDecimal("3000.00"), new BigDecimal("3000.00"),
                PriceChangeReason.PRODUCT_EDIT, null);

        verifyNoInteractions(priceChangeLogRepository);
    }

    @Test
    void testScaleDifferenceIsNotAChange() {
        // 🔴 compareTo 회귀: equals 로 판정하면 4000 → 4000.00 이 매 재생성마다 행을 만든다.
        recorder.recordProductCost(product(), new BigDecimal("4000"), new BigDecimal("4000.00"),
                PriceChangeReason.PURCHASE_UPDATE, null);

        verifyNoInteractions(priceChangeLogRepository);
    }

    @Test
    void testNullOldPriceNotRecorded() {
        // 🔴 이전 값이 없으면 변동이 아니라 최초 설정(옵션 추가)이다.
        recorder.recordSellingPrice(option(cell(ListingStatus.SELLING), null),
                null, new BigDecimal("6000"), PriceChangeReason.PROPAGATION);

        verifyNoInteractions(priceChangeLogRepository);
    }

    @Test
    void testDraftCellNotRecorded() {
        // 🔴 채널 추가·임포트가 만드는 셀이 DRAFT 다 — 생성이 이력으로 새는 자리.
        recorder.recordSellingPrice(option(cell(ListingStatus.DRAFT), new BigDecimal("6000")),
                new BigDecimal("6000"), new BigDecimal("7000"), PriceChangeReason.PROPAGATION);

        verifyNoInteractions(priceChangeLogRepository);
    }

    @Test
    void testSellingCellRecorded() {
        ProductListing cell = cell(ListingStatus.SELLING);
        recorder.recordSellingPrice(option(cell, new BigDecimal("6000")),
                new BigDecimal("6000"), new BigDecimal("7000"), PriceChangeReason.PROPAGATION);

        List<PriceChangeLog> saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getTargetType()).isEqualTo(PriceTargetType.LISTING_SELLING);
        assertThat(saved.get(0).getProduct()).isNull();
        assertThat(saved.get(0).getListingOption().getId()).isEqualTo(201L);
        assertThat(saved.get(0).getNewPrice()).isEqualByComparingTo("7000");
    }

    @Test
    void testBatchWritesOneSaveAllAndSkipsUnchanged() {
        ProductListing cell = cell(ListingStatus.SELLING);
        recorder.recordSellingPrices(cell, List.of(
                new PriceHistoryRecorder.SellingPriceChange(option(cell, null),
                        new BigDecimal("6000"), new BigDecimal("7000")),
                new PriceHistoryRecorder.SellingPriceChange(option(cell, null),
                        new BigDecimal("5000"), new BigDecimal("5000")),
                new PriceHistoryRecorder.SellingPriceChange(option(cell, null),
                        null, new BigDecimal("4000"))
        ), PriceChangeReason.PROPAGATION);

        // 파급 한 번에 수백 행이 생길 수 있어 saveAll 1회로 저장한다(행마다 save 금지).
        assertThat(captureSaved()).hasSize(1);
    }

    @Test
    void testCreatedByFromSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@test.com", "n/a", List.of()));

        recorder.recordProductCost(product(), new BigDecimal("3000"), new BigDecimal("4000"),
                PriceChangeReason.PRODUCT_EDIT, null);

        assertThat(captureSaved().get(0).getCreatedBy()).isEqualTo("admin@test.com");
    }

    @Test
    void testRecorderFailureDoesNotPropagate() {
        // ⚠️ 가격은 이미 바뀌었다 — 이력을 못 남겼다고 그 변경을 되돌리면 더 나쁘다.
        willThrow(new RuntimeException("db down")).given(priceChangeLogRepository).saveAll(anyList());

        assertThatCode(() -> recorder.recordProductCost(product(), new BigDecimal("3000"),
                new BigDecimal("4000"), PriceChangeReason.PRODUCT_EDIT, null))
                .doesNotThrowAnyException();
    }
}
