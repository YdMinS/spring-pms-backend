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
 * 채널별 지급 묶음 집계 쿼리 (FEATURE_2609_30 / PLAN D4 · D5-5).
 *
 * <p>🔴 <b>이 규칙들은 JPQL 안에 있어 목으로는 검증되지 않는다</b> — "받을 돈"에 기간이 걸리지 않는 것,
 * 지급 확정액에만 기간이 걸리는 것, 그리고 <b>{@code payoutCount} 가 상태와 무관하게 전부 센다</b>는 것이다.
 *
 * <p>마지막 항목이 화면의 배지를 가른다: 정산 묶음이 아직 없는 채널({@code payoutCount = 0})과 전부 금액이
 * 맞는 채널은 둘 다 {@code unreconciledPayouts = 0} 이라, 이 값이 없으면 정산 전 채널에 "금액 일치"가 뜬다.
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

    /** 상태·대사 결과와 무관하게 전 묶음을 센다 — "정산 이력이 있는가"에 답하는 값이라 필터를 걸면 안 된다. */
    @Test
    void payoutCountCountsEveryPayoutRegardlessOfStatus() {
        // ⚠️ settlementDate 를 다르게 준다 — 유일키가 (계정, 인식월, 유형, 정산일)이라(D5-3)
        // 같은 달에 여러 묶음이 온다는 것은 곧 정산일이 다르다는 뜻이다.
        payout(1, SettlementPayoutStatus.SCHEDULED, SettlementReconStatus.PENDING, "100000", null);
        payout(2, SettlementPayoutStatus.PAID, SettlementReconStatus.RECONCILED, "200000",
                LocalDate.of(2026, 9, 15));
        payout(3, SettlementPayoutStatus.PAID, SettlementReconStatus.UNRECONCILED, "300000",
                LocalDate.of(2026, 9, 20));
        payout(4, SettlementPayoutStatus.PAID, SettlementReconStatus.AMOUNT_ONLY, "50000",
                // 기간 밖 입금 — payoutCount 에는 들어가지만 paidAmount 에는 안 들어간다.
                LocalDate.of(2026, 8, 20));
        em.flush();

        PayoutAggregate row = aggregate();

        assertThat(row.payoutCount()).isEqualTo(4L);
        assertThat(row.unreconciledPayouts()).isEqualTo(1L);
        assertThat(row.amountOnlyPayouts()).isEqualTo(1L);
        // 받을 돈 = SCHEDULED 만, 기간 무관(D4).
        assertThat(row.pendingPayout()).isEqualByComparingTo("100000");
        // 지급 확정 = PAID 이면서 기간 내 입금분만(8월 건 제외).
        assertThat(row.paidAmount()).isEqualByComparingTo("500000");
    }

    /**
     * 🔴 묶음이 없는 계정은 {@code group by} 라 <b>행 자체가 나오지 않는다</b>.
     *
     * <p>그래서 서비스가 {@code PayoutAggregate.empty} 로 메우고, 그 0 이 화면의 "정산 이력 없음"이 된다.
     * 이 쿼리가 빈 행이라도 돌려주기 시작하면 그 구분이 조용히 무너진다.
     */
    @Test
    void accountWithoutPayoutsProducesNoRow() {
        assertThat(settlementPayoutRepository.aggregateByAccount(seller.getId(), FROM, TO,
                SettlementPayoutStatus.SCHEDULED, SettlementPayoutStatus.PAID,
                SettlementReconStatus.UNRECONCILED, SettlementReconStatus.AMOUNT_ONLY)).isEmpty();
    }

    private PayoutAggregate aggregate() {
        List<PayoutAggregate> rows = settlementPayoutRepository.aggregateByAccount(seller.getId(), FROM, TO,
                SettlementPayoutStatus.SCHEDULED, SettlementPayoutStatus.PAID,
                SettlementReconStatus.UNRECONCILED, SettlementReconStatus.AMOUNT_ONLY);
        assertThat(rows).singleElement().extracting(PayoutAggregate::accountId).isEqualTo(account.getId());
        return rows.get(0);
    }

    private void payout(int day, SettlementPayoutStatus status, SettlementReconStatus recon,
                        String finalAmount, LocalDate finalSettlementDate) {
        em.persist(SettlementPayout.builder()
                .marketplaceAccount(account)
                .settlementType(SettlementType.WEEKLY)
                .revenueRecognitionMonth("2026-09")
                .settlementDate(LocalDate.of(2026, 9, day))
                .finalSettlementDate(finalSettlementDate)
                .finalAmount(new BigDecimal(finalAmount))
                .status(status)
                .reconStatus(recon)
                .build());
    }
}
