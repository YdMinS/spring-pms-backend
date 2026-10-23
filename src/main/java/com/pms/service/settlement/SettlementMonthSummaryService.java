package com.pms.service.settlement;

import com.pms.dto.response.SettlementMonthSummaryResponse;

/**
 * This month's payout summary (FEATURE_2610_29 D26 · D30). No input — the server picks this month in KST; the date
 * of a payout = its confirmed payout date, else its scheduled payout date (the same basis as the sales screen
 * {@code paidAmount}).
 *
 * <p>⚠️ Not the {@code /payouts} list — that list cuts by the scheduled payout date (D26).
 */
public interface SettlementMonthSummaryService {

    SettlementMonthSummaryResponse thisMonth();
}
