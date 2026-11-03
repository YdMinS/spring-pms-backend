package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementPayoutStatus;
import com.pms.domain.SettlementReconStatus;
import com.pms.domain.SettlementType;
import com.pms.dto.response.PayoutAggregate;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.security.crypto.AesAttributeConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-channel payout aggregate query (FEATURE_2609_30 / PLAN D4 · D5-5).
 *
 * <p>🔴 <b>These rules are inside the JPQL, so mocks cannot check them</b> — the scheduled sum takes no period, and
 * only the paid amount takes the period. Swap the two and the 「지급확정」 cell silently carries the scheduled sum (the
 * sales screens show only the paid amount since FEATURE_2611_05 D9).
 *
 * <p>🔴 This aggregate does not count reconciliation statuses (FEATURE_2609_34) — a count without the period, shown
 * as a badge on a period row, misleads. Reconciliation status is shown per payout by the recognition-month list.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TestJpaConfig.class, AesAttributeConverter.class})
class SettlementPayoutAggregationTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Autowired private SettlementPayoutRepository settlementPayoutRepository;
    @Autowired private TestEntityManager em;

    private Seller seller;
    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        seller = em.persist(Seller.builder().sellerName("셀러A").businessRegistration("111-11-11111").build());
        account = MarketplaceAccountFixture.coupangAccount(em, seller);
    }

    /**
     * 받을 돈은 기간 무관(D4)이고, 지급 확정만 기간을 탄다(D4-1).
     *
     * <p>🔴 대사 상태별 건수는 <b>이 집계에 없다</b>(FEATURE_2609_34) — 기간이 걸리지 않는 건수를 기간
     * 필터가 달린 화면 행에 배지로 걸면 "이 기간에 N건이 어긋났다"로 읽힌다. 그래서 여기서 세지 않는다.
     */
    @Test
    void pendingIgnoresPeriodWhilePaidHonorsIt() {
        // ⚠️ 지급일(settlementDate)은 유일키의 일부이기도 하다(계정, 인식월, 유형, 정산일 — D5-3).
        payout(LocalDate.of(2026, 9, 1), SettlementPayoutStatus.SCHEDULED,
                SettlementReconStatus.PENDING, "100000");
        payout(LocalDate.of(2026, 9, 15), SettlementPayoutStatus.PAID,
                SettlementReconStatus.RECONCILED, "200000");
        payout(LocalDate.of(2026, 9, 20), SettlementPayoutStatus.PAID,
                SettlementReconStatus.UNRECONCILED, "300000");
        // 🔴 기간 밖 입금 — 인식월은 같아도(9월) 돈이 8월에 들어왔으면 이 기간의 입금이 아니다.
        payout(LocalDate.of(2026, 8, 20), SettlementPayoutStatus.PAID,
                SettlementReconStatus.AMOUNT_ONLY, "50000");
        em.flush();

        PayoutAggregate row = aggregate();

        // 받을 돈 = SCHEDULED 만, 기간 무관(D4).
        assertThat(row.pendingPayout()).isEqualByComparingTo("100000");
        // 지급 확정 = PAID 이면서 기간 내 입금분만(8월 건 제외).
        assertThat(row.paidAmount()).isEqualByComparingTo("500000");
    }

    /**
     * FEATURE_2610_20 / D38: an 11st payout marked paid counts on the day the money actually arrived
     * ({@code finalSettlementDate}), not on its remittance date.
     */
    @Test
    void paidAmountUsesTheConfirmedDateWhenPresent() {
        paidOn(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 2), "70000");   // arrived after the period
        paidOn(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 2), "40000");    // arrived inside the period
        em.flush();

        assertThat(aggregate().paidAmount()).isEqualByComparingTo("40000");
    }

    /**
     * 🔴 An account without payouts gives <b>no row at all</b> — the query is a {@code group by}.
     *
     * <p>So the service fills it with {@code PayoutAggregate.empty} — without that 0, the 「지급확정」 cell of a channel
     * with no settlement history would be empty.
     */
    @Test
    void accountWithoutPayoutsProducesNoRow() {
        assertThat(settlementPayoutRepository.aggregateByAccount(seller.getId(), FROM, TO,
                SettlementPayoutStatus.SCHEDULED, SettlementPayoutStatus.PAID)).isEmpty();
    }

    private PayoutAggregate aggregate() {
        List<PayoutAggregate> rows = settlementPayoutRepository.aggregateByAccount(seller.getId(), FROM, TO,
                SettlementPayoutStatus.SCHEDULED, SettlementPayoutStatus.PAID);
        assertThat(rows).singleElement().extracting(PayoutAggregate::accountId).isEqualTo(account.getId());
        return rows.get(0);
    }

    /** A paid DAILY payout with its actual arrival date filled (the 11st 「지급 완료」 mark). */
    private void paidOn(LocalDate settlementDate, LocalDate arrivedOn, String finalAmount) {
        em.persist(SettlementPayout.builder()
                .marketplaceAccount(account)
                .settlementType(SettlementType.DAILY)
                .revenueRecognitionMonth(settlementDate.toString().substring(0, 7))
                .settlementDate(settlementDate)
                .finalSettlementDate(arrivedOn)
                .finalAmount(new BigDecimal(finalAmount))
                .status(SettlementPayoutStatus.PAID)
                .reconStatus(SettlementReconStatus.NO_TOTAL)
                .build());
    }

    /**
     * 🔴 Does not fill {@code finalSettlementDate} — the Coupang payment-history API never sends that field, so a
     * Coupang payout keeps it NULL (only an 11st payout marked 「지급 완료」 has it — {@link #paidOn}). Filling it here
     * would make a net that still passes when the date basis is reverted.
     */
    private void payout(LocalDate settlementDate, SettlementPayoutStatus status,
                        SettlementReconStatus recon, String finalAmount) {
        em.persist(SettlementPayout.builder()
                .marketplaceAccount(account)
                .settlementType(SettlementType.WEEKLY)
                .revenueRecognitionMonth("2026-09")
                .settlementDate(settlementDate)
                .finalAmount(new BigDecimal(finalAmount))
                .status(status)
                .reconStatus(recon)
                .build());
    }
}
