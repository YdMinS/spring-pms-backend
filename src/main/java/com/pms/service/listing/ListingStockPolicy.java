package com.pms.service.listing;

import com.pms.domain.MasterProductOption;
import com.pms.domain.ProductListingOption;

/**
 * Stock-quantity resolution for options (FEATURE_2608_06 / 102). The single place that knows the rule
 * {@code channel ?? master ?? 9999}, shared by four callers: the Coupang payload builder, the per-channel
 * stock write (ceiling validation), the master-save clamp, and the matrix/option responses (maxStock).
 *
 * <p>⚠️ Keep it stateless and logic-free — a follow-up may turn the master value from "typed in by hand" into
 * a computed one (per-product / per-seller stock), and one source keeps the four callers from drifting.</p>
 */
public final class ListingStockPolicy {

    /**
     * Fallback when neither the channel nor the master option carries a stock value (Coupang's cap is 99999).
     * ⚠️ Do NOT remove this fallback: every pre-102 option row is null, so dropping it would push every
     * re-registered product as sold out.
     */
    public static final int DEFAULT_STOCK_QUANTITY = 9999;

    private ListingStockPolicy() {
    }

    /**
     * Upper bound for a channel override = the master option's stock, or {@link #DEFAULT_STOCK_QUANTITY} when
     * the master leaves it unset. ⚠️ {@code master == null} is a normal input (a renamed/legacy channel option
     * that matches no master option), not an error → 9999.
     */
    public static int ceiling(MasterProductOption master) {
        return master == null || master.getStockQuantity() == null
                ? DEFAULT_STOCK_QUANTITY
                : master.getStockQuantity();
    }

    /**
     * Value actually pushed to the market = channel override ?? master default ?? {@link #DEFAULT_STOCK_QUANTITY}.
     * Both arguments may be null. ⚠️ 0 is a real value (sold out), never treated as "unset".
     */
    public static int resolve(ProductListingOption channel, MasterProductOption master) {
        if (channel != null && channel.getStockQuantity() != null) {
            return channel.getStockQuantity();
        }
        return ceiling(master);
    }
}
