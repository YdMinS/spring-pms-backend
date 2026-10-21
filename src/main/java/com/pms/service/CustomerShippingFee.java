package com.pms.service;

import java.math.BigDecimal;

/**
 * The shipping fee a buyer pays for ONE unit, as the 11st delivery-fee commission sees it (FEATURE_2610_13 /
 * D16 · D92 · D106 · D111) — and the ESM one (FEATURE_2610_26 / D60: the chosen bundle-fee policy's prepaid fee).
 * {@code freeShipOver} is set only for 「조건부 무료」: at or above that price the buyer pays nothing.
 *
 * <p>{@link #costAt} = the 3.3% commission 11st or ESM takes on that fee (VAT already inside the 3.3% — never
 * multiply by 1.1 again, D16). {@link #NONE} = no commission (every cell but 11st and ESM, free shipping, cash on
 * delivery …).</p>
 */
public record CustomerShippingFee(BigDecimal charge, BigDecimal freeShipOver) {

    /** D16: 11st delivery-fee commission rate, VAT included — the ESM rate too (FEATURE_2610_26 / D60). */
    public static final BigDecimal RATE = new BigDecimal("0.033");

    public static final CustomerShippingFee NONE = new CustomerShippingFee(BigDecimal.ZERO, null);

    /**
     * The commission cost at a given selling price. D92: the 「조건부 무료」 test uses the price as given — the
     * caller passes the price BEFORE this cost was added (one judgement, no re-judging).
     */
    public BigDecimal costAt(BigDecimal price) {
        boolean free = freeShipOver != null && price != null && price.compareTo(freeShipOver) >= 0;
        return free ? BigDecimal.ZERO : charge.multiply(RATE);
    }
}
