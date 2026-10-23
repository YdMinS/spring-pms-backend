package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.domain.SettlementPayout;
import com.pms.domain.SettlementPayoutStatus;
import com.pms.domain.SettlementReconStatus;
import com.pms.domain.SettlementType;
import com.pms.dto.response.PayoutMonthTotals;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard month sum (FEATURE_2610_29 D26 · D30) — {@code SettlementPayoutRepository.sumByPayoutDate}.
 *
 * <p>🔴 The date basis lives in the JPQL, so a mock cannot check it: a payout belongs to the month of its confirmed
 * payout date, else its scheduled one. With the {@code /payouts} basis (the scheduled date) September would be
 * 370000 / 270000 instead of 340000 / 240000.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({TestJpaConfig.class, AesAttributeConverter.class})
class SettlementPayoutMonthSumTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Autowired private SettlementPayoutRepository settlementPayoutRepository;
    @Autowired private TestEntityManager em;

    private MarketplaceAccount account;

    @BeforeEach
    void setUp() {
        Seller seller = em.persist(Seller.builder().sellerName("셀러A").businessRegistration("111-11-11111").build());
        account = MarketplaceAccountFixture.coupangAccount(em, seller);
    }

    @Test
    void monthSumUsesTheConfirmedDateElseTheScheduledDate() {
        payout(LocalDate.of(2026, 9, 4), null, SettlementPayoutStatus.SCHEDULED, "100000");  // scheduled inside
        payout(LocalDate.of(2026, 9, 15), null, SettlementPayoutStatus.PAID, "200000");      // scheduled inside
        payout(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 2),
                SettlementPayoutStatus.PAID, "40000");                                        // confirmed inside
        payout(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 2),
                SettlementPayoutStatus.PAID, "70000");                                        // confirmed after
        em.flush();

        PayoutMonthTotals september = settlementPayoutRepository.sumByPayoutDate(FROM, TO,
                SettlementPayoutStatus.PAID);

        assertThat(september.total()).isEqualByComparingTo("340000");
        assertThat(september.paid()).isEqualByComparingTo("240000");
    }

    @Test
    void monthWithoutPayoutsSumsToZero() {
        PayoutMonthTotals september = settlementPayoutRepository.sumByPayoutDate(FROM, TO,
                SettlementPayoutStatus.PAID);

        assertThat(september.total()).isEqualByComparingTo("0");
        assertThat(september.paid()).isEqualByComparingTo("0");
    }

    /** {@code confirmedOn} = null keeps the payout on its scheduled date (a Coupang payout does not carry one). */
    private void payout(LocalDate settlementDate, LocalDate confirmedOn, SettlementPayoutStatus status,
                        String finalAmount) {
        em.persist(SettlementPayout.builder()
                .marketplaceAccount(account)
                .settlementType(SettlementType.DAILY)
                .revenueRecognitionMonth(settlementDate.toString().substring(0, 7))
                .settlementDate(settlementDate)
                .finalSettlementDate(confirmedOn)
                .finalAmount(new BigDecimal(finalAmount))
                .status(status)
                .reconStatus(SettlementReconStatus.NO_TOTAL)
                .build());
    }
}
