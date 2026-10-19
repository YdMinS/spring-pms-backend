package com.pms.service.settlement;

import java.time.LocalDate;

/**
 * The person's 「지급 완료」 mark on an 11st payout (FEATURE_2610_20 / D34 · D36 · D38 · D39).
 *
 * <p>11st never says whether it actually remitted, so an 11st payout stays 「지급 예정」 until an admin marks it.
 * Coupang payouts are not markable — Coupang reports their status itself (D34).</p>
 *
 * <p>⚠️ The mark lives in {@code settlement_payout.status} + {@code final_settlement_date}; the 11st payout rebuild
 * never overwrites them (D35).</p>
 */
public interface SettlementPaymentService {

    /**
     * Status → PAID, confirmed payout date → {@code paidOn}. Marking an already paid payout again only moves the date.
     *
     * @throws IllegalArgumentException unknown payout · not an 11st payout · {@code paidOn} missing · {@code paidOn}
     *                                  after today, KST (→ 400)
     */
    void markPaid(Long payoutId, LocalDate paidOn);

    /**
     * Undo (D39): status → SCHEDULED, confirmed payout date cleared.
     *
     * @throws IllegalArgumentException unknown payout · not an 11st payout (→ 400)
     */
    void unmarkPaid(Long payoutId);
}
