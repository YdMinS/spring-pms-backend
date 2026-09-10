package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.SaleType;
import com.pms.domain.Seller;
import com.pms.domain.SettlementLine;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementPayoutStatus;
import com.pms.domain.SettlementType;
import com.pms.fixture.MarketplaceAccountFixture;
import com.pms.security.crypto.AesAttributeConverter;
import com.pms.service.settlement.SettlementReconciler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인식월 집계 쿼리 (FEATURE_2609_32 / PLAN 2609_32 D6·D7).
 *
 * <p>🔴 라인 부호 규칙(REFUND = 음수)이 SQL 과 {@link SettlementReconciler#lineTotal} <b>두 곳</b>에 있다.
 * 월 전체 라인을 메모리에 올릴 수 없어 감수한 예외라, 두 답이 같은지 여기서 <b>동치 테스트</b>로 묶는다 —
 * 한쪽만 바뀌면 화면의 월 합계가 조용히 어긋난다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TestJpaConfig.class, AesAttributeConverter.class})
class SettlementMonthAggregationTest {

    private static final String MONTH = "2026-08";
    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    @Autowired private SettlementLineRepository settlementLineRepository;
    @Autowired private SettlementPayoutRepository settlementPayoutRepository;
    @Autowired private TestEntityManager em;

    private final SettlementReconciler reconciler = new SettlementReconciler(BigDecimal.ONE);

    private Seller seller;
    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        seller = em.persist(Seller.builder().sellerName("셀러A").businessRegistration("111-11-11111").build());
        account = MarketplaceAccountFixture.coupangAccount(em, seller);
    }

    /** 🔴 D7 동치 테스트 — 쿼리 합계 == Reconciler 합계. 반환 타입이 BigDecimal 인 것도 여기서 드러난다. */
    @Test
    void signedLineSumMatchesReconciler() {
        List<SettlementLine> lines = new ArrayList<>();
        lines.add(line(account, SaleType.SALE, "12000", LocalDate.of(2026, 8, 3)));
        lines.add(line(account, SaleType.SALE, "34500", LocalDate.of(2026, 8, 10)));
        lines.add(line(account, SaleType.SALE, "990", LocalDate.of(2026, 8, 31)));
        lines.add(line(account, SaleType.REFUND, "7000", LocalDate.of(2026, 8, 12)));
        lines.add(line(account, SaleType.REFUND, "1250", LocalDate.of(2026, 8, 20)));
        lines.add(line(account, SaleType.SALE, null, LocalDate.of(2026, 8, 21)));
        em.flush();
        em.clear();

        BigDecimal queried = settlementLineRepository
                .sumSignedSettlementAmount(account.getId(), FROM, TO, SaleType.REFUND);

        assertThat(queried).isEqualByComparingTo(reconciler.lineTotal(lines));
        assertThat(queried).isEqualByComparingTo("39240");
        assertThat(settlementLineRepository
                .countByMarketplaceAccount_IdAndRecognitionDateBetween(account.getId(), FROM, TO))
                .isEqualTo(6);
    }

    @Test
    void lineSumIgnoresOtherAccountsAndMonths() {
        line(account, SaleType.SALE, "10000", LocalDate.of(2026, 8, 5));
        MarketplaceAccount other = MarketplaceAccountFixture.coupangAccount(em, seller, "V2");
        line(other, SaleType.SALE, "999999", LocalDate.of(2026, 8, 5));
        line(account, SaleType.SALE, "888888", LocalDate.of(2026, 7, 31));
        line(account, SaleType.SALE, "777777", LocalDate.of(2026, 9, 1));
        em.flush();
        em.clear();

        assertThat(settlementLineRepository
                .sumSignedSettlementAmount(account.getId(), FROM, TO, SaleType.REFUND))
                .isEqualByComparingTo("10000");
        assertThat(settlementLineRepository
                .countByMarketplaceAccount_IdAndRecognitionDateBetween(account.getId(), FROM, TO))
                .isEqualTo(1);
    }

    /** 🔴 D6 — finalAmount 미수신 건은 합에서 빼고 건수로만 드러낸다. 0으로 더하면 차이가 항상 우리 쪽 초과가 된다. */
    @Test
    void payoutSumSkipsMissingFinalAmount() {
        payout(SettlementType.WEEKLY, "500000", LocalDate.of(2026, 9, 1));
        payout(SettlementType.WEEKLY, "300000", LocalDate.of(2026, 9, 8));
        payout(SettlementType.ADDITIONAL, "350000", LocalDate.of(2026, 9, 15));
        payout(SettlementType.WEEKLY, null, LocalDate.of(2026, 9, 22));
        em.flush();
        em.clear();

        assertThat(settlementPayoutRepository.sumFinalAmountByMonth(account.getId(), MONTH))
                .isEqualByComparingTo("1150000");
        assertThat(settlementPayoutRepository
                .countByMarketplaceAccount_IdAndRevenueRecognitionMonth(account.getId(), MONTH))
                .isEqualTo(4);
        assertThat(settlementPayoutRepository
                .countByMarketplaceAccount_IdAndRevenueRecognitionMonthAndFinalAmountIsNull(
                        account.getId(), MONTH))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------- fixtures

    /** 🔴 유일키가 (계정, 주문번호, 옵션, 판매유형, 인식일)이라 같은 달 라인은 주문번호를 다르게 준다. */
    private SettlementLine line(MarketplaceAccount owner, SaleType saleType, String settlementAmount,
                                LocalDate recognitionDate) {
        return em.persist(SettlementLine.builder()
                .marketplaceAccount(owner)
                .externalOrderId("O-" + System.nanoTime())
                .platformOptionId("VI-1")
                .saleType(saleType)
                .settlementAmount(settlementAmount == null ? null : new BigDecimal(settlementAmount))
                .recognitionDate(recognitionDate)
                .build());
    }

    private void payout(SettlementType type, String finalAmount, LocalDate settlementDate) {
        em.persist(SettlementPayout.builder()
                .marketplaceAccount(account)
                .settlementType(type)
                .revenueRecognitionMonth(MONTH)
                .settlementDate(settlementDate)
                .finalAmount(finalAmount == null ? null : new BigDecimal(finalAmount))
                .status(SettlementPayoutStatus.SCHEDULED)
                .build());
    }
}
