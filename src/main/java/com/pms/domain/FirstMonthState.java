package com.pms.domain;

/**
 * State of the first-month (M0) judgement of an 11st server-fee link (FEATURE_2610_20 / D45 · D46).
 *
 * <p>Stored in {@code marketplace_account_fixed_cost.first_month_state}; NULL = not judged yet. Only links to an
 * 11st catalog item ever get a value — Coupang links keep NULL.</p>
 *
 * <ul>
 *   <li>{@link #AUTO} — M0's previous month has 11st settlement lines; M0 is charged by the D42 rule.</li>
 *   <li>{@link #ASKING} — that month could not be loaded (failure / 0 rows); the 「정산 내역」 screen asks the person.
 *       M0 is 0 won while asking (no lines in the previous month).</li>
 *   <li>{@link #ANSWERED} — the person answered; {@code first_month_charged} decides M0 and never changes again.</li>
 * </ul>
 */
public enum FirstMonthState {

    AUTO,
    ASKING,
    ANSWERED
}
