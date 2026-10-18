package com.pms.domain;

/**
 * Processing state of one (product × seller) share on the purchase list (FEATURE_2610_09 / D57 · D61 · D80 · D85).
 *
 * <ul>
 *   <li>{@link #PURCHASED} — the buyer pressed [매입 완료]</li>
 *   <li>{@link #NOT_NEEDED} — [오늘 매입 안 함] with the reason 「매입할 필요 없음」 (D66)</li>
 *   <li>{@link #TOMORROW} — [오늘 매입 안 함] with the reason 「내일 매입」; valid on its check day only (D67)</li>
 *   <li>{@link #NEED_ZERO} — written by the extract when the share's needed quantity fell from above 0 to 0 (D85).
 *       Never sent by a screen.</li>
 * </ul>
 */
public enum PurchaseCheckStatus {
    PURCHASED,
    NOT_NEEDED,
    TOMORROW,
    NEED_ZERO
}
