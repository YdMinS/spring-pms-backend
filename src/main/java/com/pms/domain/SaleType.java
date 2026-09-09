package com.pms.domain;

/**
 * Direction of a settlement line (FEATURE_2609_30 / PLAN D5).
 *
 * <p>🔴 Coupang sends every amount as a POSITIVE number, including refunds — the meaning of the sign
 * lives here, not in the stored amounts. Do not flip amounts to negative on load: the mirror must
 * stay byte-comparable with the platform response.
 *
 * <p>Part of the line's unique key: the same (order, option, recognition date) can carry both a sale
 * and a refund row.
 */
public enum SaleType {

    SALE,
    REFUND
}
