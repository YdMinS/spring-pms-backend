package com.pms.domain;

/**
 * How a channel bears a fixed-cost item (FEATURE_2609_33 / PLAN 2609_33 D2).
 *
 * <p>The default is {@link #AUTO} — charged when the month's channel sales reach the threshold (an 11st item: the
 * previous month's 11st purchase-confirmed sales, FEATURE_2610_20 / D42). Our sales axis is <b>not guaranteed to match
 * the platform's recognition basis</b> (D11 · D11-1), so a month near the threshold can go either way. When the real
 * bill disagrees with our judgement the person wins → {@link #ALWAYS} · {@link #NEVER}.
 *
 * <p>⚠️ No per-month exception table (D2) — handled only <b>once 3 values prove not enough</b>. The one exception is
 * the 11st first month (M0), kept as three columns on the link, not a table (FEATURE_2610_20 / D41 · D46).
 */
public enum FixedCostChargeMode {

    /** Charged when the month's sales >= threshold (default; 11st item: the previous month's, D42). */
    AUTO,

    /** 매출과 무관하게 매달 부과 — 실제 청구가 계속 되는 것이 확인된 채널. */
    ALWAYS,

    /** 부과하지 않는다 — 우리 판정이 임계를 넘겨도 실제로는 청구되지 않는 채널. */
    NEVER
}
