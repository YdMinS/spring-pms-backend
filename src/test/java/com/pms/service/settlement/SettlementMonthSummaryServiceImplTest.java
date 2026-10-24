package com.pms.service.settlement;

import com.pms.domain.SettlementPayoutStatus;
import com.pms.dto.response.PayoutMonthTotals;
import com.pms.dto.response.SettlementMonthSummaryResponse;
import com.pms.repository.SettlementPayoutRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * FEATURE_2610_29 D30 — the server picks this month in KST, passes the seller through, and remaining = total − paid.
 */
@ExtendWith(MockitoExtension.class)
class SettlementMonthSummaryServiceImplTest {

    @Mock private SettlementPayoutRepository settlementPayoutRepository;

    /**
     * 🔴 2026-09-30 15:30 UTC is already 2026-10-01 00:30 in KST — the month must be October, not September.
     */
    @Test
    void thisMonthIsTheKstMonthAndRemainingIsTotalMinusPaid() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-30T15:30:00Z"), ZoneOffset.UTC);
        given(settlementPayoutRepository.sumByPayoutDate(7L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                SettlementPayoutStatus.PAID))
                .willReturn(new PayoutMonthTotals(new BigDecimal("340000"), new BigDecimal("240000")));

        SettlementMonthSummaryResponse summary =
                new SettlementMonthSummaryServiceImpl(settlementPayoutRepository, clock).thisMonth(7L);

        assertThat(summary.total()).isEqualByComparingTo("340000");
        assertThat(summary.paid()).isEqualByComparingTo("240000");
        assertThat(summary.remaining()).isEqualByComparingTo("100000");
    }
}
