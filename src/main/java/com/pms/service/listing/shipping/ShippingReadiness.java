package com.pms.service.listing.shipping;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a resolved shipping config is complete enough to register on the market
 * (FEATURE_2608_06 / 77). Extracted from {@code CoupangListingAdapter.requireShippingConfig} so the very
 * same judgement powers two callers with no drift:
 *
 * <ul>
 *   <li>the register path — the adapter throws 400 on a violation (final safety net, unchanged messages);</li>
 *   <li>the read path — {@code GeneratedProductResponse.shippingReady}, so the UI can guard the
 *       [마켓 등록] button before the user ever hits the 400.</li>
 * </ul>
 *
 * <p>⚠️ <b>Coupang rules.</b> The required-field list and the 묶음배송/착불 conflict are Coupang's registration
 * policy (migrated as-is from the adapter). NAVER (3d) has a different required set — when that adapter
 * lands, branch per platform here (or give each adapter its own readiness impl); do NOT widen this one into
 * a "neutral" list. The channel seam ({@link com.pms.service.listing.ListingChannel#isShippingReady}) owns
 * the choice, this class only holds the Coupang rules.</p>
 */
public final class ShippingReadiness {

    private ShippingReadiness() {
    }

    /**
     * Readiness verdict for one resolved config.
     *
     * @param missing              names of the register-required fields that are null/blank (empty = all present)
     * @param unionChargeConflict  묶음배송(UNION_DELIVERY) combined with 착불(CHARGE_RECEIVED) — forbidden by Coupang
     */
    public record Readiness(List<String> missing, boolean unionChargeConflict) {

        /** Registerable = nothing missing and no forbidden combination. */
        public boolean ready() {
            return missing.isEmpty() && !unionChargeConflict;
        }
    }

    /** Evaluate a resolved config (channel ?? master ?? account default, 75) against Coupang's register rules. */
    public static Readiness check(ResolvedShippingConfig cfg) {
        List<String> missing = new ArrayList<>();
        requireText(missing, "outboundShippingPlaceCode", cfg.outboundShippingPlaceCode());
        requireText(missing, "returnCenterCode", cfg.returnCenterCode());
        requireText(missing, "returnChargeName", cfg.returnChargeName());
        requireText(missing, "returnContactNumber", cfg.returnContactNumber());
        requireText(missing, "returnZipCode", cfg.returnZipCode());
        requireText(missing, "returnAddress", cfg.returnAddress());
        requireText(missing, "returnAddressDetail", cfg.returnAddressDetail());
        requireValue(missing, "returnCharge", cfg.returnCharge());
        requireValue(missing, "deliveryChargeOnReturn", cfg.deliveryChargeOnReturn());
        requireText(missing, "deliveryMethod", cfg.deliveryMethod());
        requireText(missing, "deliveryCompanyCode", cfg.deliveryCompanyCode());
        requireText(missing, "deliveryChargeType", cfg.deliveryChargeType());
        requireValue(missing, "deliveryCharge", cfg.deliveryCharge());
        requireText(missing, "remoteAreaDeliverable", cfg.remoteAreaDeliverable());
        requireText(missing, "unionDeliveryType", cfg.unionDeliveryType());
        // freeShipOverAmount stays optional (only relevant for CONDITIONAL_FREE).

        boolean unionChargeConflict = "UNION_DELIVERY".equals(cfg.unionDeliveryType())
                && "CHARGE_RECEIVED".equals(cfg.deliveryChargeType());
        return new Readiness(List.copyOf(missing), unionChargeConflict);
    }

    private static void requireText(List<String> missing, String name, String value) {
        if (value == null || value.isBlank()) {
            missing.add(name);
        }
    }

    private static void requireValue(List<String> missing, String name, Object value) {
        if (value == null) {
            missing.add(name);
        }
    }
}
