package com.pms.service.esm;

import java.math.BigDecimal;

/**
 * ESM price rule on our side (FEATURE_2610_26 / D53 ②): a price typed by a person on an ESM listing — Gmarket or
 * Auction — must be a 10-won unit (post 20 「10원 단위」), else the whole request is refused with
 * {@link #PRICE_UNIT_MESSAGE}. Calculated prices are already 10-won units (PriceCalculator).
 */
public final class EsmPriceRules {

    public static final String PRICE_UNIT_MESSAGE = "G마켓 · 옥션 판매가는 10원 단위로 입력하세요";

    private EsmPriceRules() {
    }

    /** True when {@code price} is a whole multiple of 10 won. */
    public static boolean isTenWonUnit(BigDecimal price) {
        return price != null && price.remainder(BigDecimal.TEN).compareTo(BigDecimal.ZERO) == 0;
    }
}
