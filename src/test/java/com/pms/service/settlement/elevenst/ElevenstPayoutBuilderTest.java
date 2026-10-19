package com.pms.service.settlement.elevenst;

import com.pms.domain.ElevenstSettlementLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.SaleType;
import com.pms.domain.SettlementLine;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementPayoutStatus;
import com.pms.domain.SettlementReconStatus;
import com.pms.domain.SettlementType;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.repository.ElevenstSettlementLineRepository;
import com.pms.repository.SettlementLineRepository;
import com.pms.repository.SettlementPayoutRepository;
import com.pms.service.settlement.SettlementPayoutUpserter.PayoutUpsertResult;
import com.pms.service.settlement.SettlementReconciler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 11st payouts = stored lines grouped by remittance date (FEATURE_2610_20 / D29 · D35).
 */
@ExtendWith(MockitoExtension.class)
class ElevenstPayoutBuilderTest {

    private static final LocalDate OCT_20 = LocalDate.of(2026, 10, 20);
    private static final LocalDate OCT_27 = LocalDate.of(2026, 10, 27);

    @Mock private ElevenstSettlementLineRepository elevenstSettlementLineRepository;
    @Mock private SettlementPayoutRepository settlementPayoutRepository;
    @Mock private SettlementLineRepository settlementLineRepository;

    private ElevenstPayoutBuilder builder;
    private final MarketplaceAccount account = MarketplaceAccountFixture.elevenstStubBuilder("KEY").id(9L).build();
    private final List<SettlementPayout> savedPayouts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        builder = new ElevenstPayoutBuilder(elevenstSettlementLineRepository, settlementPayoutRepository,
                settlementLineRepository, new SettlementReconciler(BigDecimal.ONE));
        given(settlementPayoutRepository.save(any())).willAnswer(invocation -> {
            SettlementPayout payout = invocation.getArgument(0);
            SettlementPayout withId = payout.getId() != null ? payout
                    : payout.toBuilder().id(100L + savedPayouts.size()).build();
            savedPayouts.add(withId);
            return withId;
        });
    }

    @Test
    void groupsLinesByRemittanceDate() {
        given(elevenstSettlementLineRepository.findByMarketplaceAccount_Id(9L)).willReturn(List.of(
                mirror(line(1L, SaleType.SALE, "9000", "10000"), OCT_20),
                mirror(line(2L, SaleType.REFUND, "1000", "1100"), OCT_20),
                mirror(line(3L, SaleType.SALE, "5000", "5500"), OCT_27)));
        given(settlementPayoutRepository.findByMarketplaceAccount_Id(9L)).willReturn(List.of());

        PayoutUpsertResult result = builder.rebuild(account);

        assertThat(result.payouts()).isEqualTo(2);
        assertThat(result.attributedLines()).isEqualTo(3);
        SettlementPayout first = savedPayouts.get(0);
        assertThat(first.getSettlementDate()).isEqualTo(OCT_20);
        assertThat(first.getRevenueRecognitionMonth()).isEqualTo("2026-10");
        assertThat(first.getSettlementType()).isEqualTo(SettlementType.DAILY);
        assertThat(first.getStatus()).isEqualTo(SettlementPayoutStatus.SCHEDULED);
        assertThat(first.getReconStatus()).isEqualTo(SettlementReconStatus.NO_TOTAL);
        assertThat(first.getFinalAmount()).isEqualByComparingTo("8000");
        assertThat(first.getTotalSale()).isEqualByComparingTo("8900");
    }

    @Test
    void existingPaidPayoutKeepsItsMark() {
        SettlementPayout paid = SettlementPayout.builder()
                .id(50L).marketplaceAccount(account).revenueRecognitionMonth("2026-10")
                .settlementType(SettlementType.DAILY).settlementDate(OCT_20)
                .status(SettlementPayoutStatus.PAID).finalSettlementDate(LocalDate.of(2026, 10, 22))
                .build();
        given(elevenstSettlementLineRepository.findByMarketplaceAccount_Id(9L)).willReturn(List.of(
                mirror(line(1L, SaleType.SALE, "9000", "10000"), OCT_20)));
        given(settlementPayoutRepository.findByMarketplaceAccount_Id(9L)).willReturn(List.of(paid));

        builder.rebuild(account);

        SettlementPayout saved = savedPayouts.get(0);
        assertThat(saved.getId()).isEqualTo(50L);
        assertThat(saved.getStatus()).isEqualTo(SettlementPayoutStatus.PAID);
        assertThat(saved.getFinalSettlementDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(saved.getFinalAmount()).isEqualByComparingTo("9000");
    }

    @Test
    void scheduledPayoutWithoutLinesIsDeleted() {
        SettlementPayout stale = SettlementPayout.builder()
                .id(60L).marketplaceAccount(account).settlementType(SettlementType.DAILY)
                .settlementDate(OCT_27).status(SettlementPayoutStatus.SCHEDULED).build();
        given(elevenstSettlementLineRepository.findByMarketplaceAccount_Id(9L)).willReturn(List.of(
                mirror(line(1L, SaleType.SALE, "9000", "10000"), OCT_20)));
        given(settlementPayoutRepository.findByMarketplaceAccount_Id(9L)).willReturn(List.of(stale));

        builder.rebuild(account);

        verify(settlementPayoutRepository).delete(stale);
        ArgumentCaptor<List<SettlementLine>> moved = ArgumentCaptor.captor();
        verify(settlementLineRepository, atLeastOnce()).saveAll(moved.capture());
        assertThat(moved.getValue()).extracting(l -> l.getSettlementPayout().getSettlementDate())
                .containsExactly(OCT_20);
    }

    private static SettlementLine line(Long id, SaleType type, String settlement, String sale) {
        return SettlementLine.builder()
                .id(id).saleType(type)
                .settlementAmount(new BigDecimal(settlement))
                .saleAmount(new BigDecimal(sale))
                .build();
    }

    private static ElevenstSettlementLine mirror(SettlementLine line, LocalDate remittance) {
        return ElevenstSettlementLine.builder().settlementLine(line).stlPlnDy(remittance).build();
    }
}
