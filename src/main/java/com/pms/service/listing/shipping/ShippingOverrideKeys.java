package com.pms.service.listing.shipping;

import com.pms.domain.Platform;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The whitelist of shipping-override keys (FEATURE_2608_06 / 75) — the single source shared by storage
 * (master/listing PATCH) and resolution ({@link CoupangShippingConfigResolver}). Values are stored as strings
 * (BigDecimal/enum too, parsed at resolve time); {@code null} on the entity = no override.
 *
 * <p>⚠️ <b>Level constraint — outbound place / return center are channel-level only.</b> Those center codes
 * ({@link #OUTBOUND_SHIPPING_PLACE_CODE}, {@link #RETURN_CENTER_CODE} + the return address block) are
 * registered per account, so their codes differ per account — a master-level override would force one
 * account's code onto every linked account and break their registrations. So the <b>master whitelist ⊂ the
 * listing whitelist</b>: the listing accepts every key of its platform; the master accepts every key EXCEPT the
 * place keys.
 * A master override carrying a place key is <b>silently dropped</b> on save (lenient, not a 400).</p>
 *
 * <p>FEATURE_2610_13 / D104: the master map also carries the 11st master keys ({@link ElevenstShippingKeys},
 * {@code elevenst.*}), and an 11st listing takes only the 11st keys — {@link #filterListing} picks the set by
 * the listing's platform. FEATURE_2610_26 / D36: an ESM listing takes only the ESM keys ({@link EsmShippingKeys},
 * {@code esm.*}; the master has none).</p>
 */
public final class ShippingOverrideKeys {

    private ShippingOverrideKeys() {
    }

    // --- outbound place + return center (channel / listing level only) ---
    public static final String OUTBOUND_SHIPPING_PLACE_CODE = "outboundShippingPlaceCode";
    public static final String RETURN_CENTER_CODE = "returnCenterCode";
    public static final String RETURN_CHARGE_NAME = "returnChargeName";
    public static final String RETURN_CONTACT_NUMBER = "returnContactNumber";
    public static final String RETURN_ZIP_CODE = "returnZipCode";
    public static final String RETURN_ADDRESS = "returnAddress";
    public static final String RETURN_ADDRESS_DETAIL = "returnAddressDetail";

    // --- master + listing level (numeric keys parsed to BigDecimal at resolve time) ---
    public static final String RETURN_CHARGE = "returnCharge";                       // numeric
    public static final String DELIVERY_CHARGE_ON_RETURN = "deliveryChargeOnReturn"; // numeric
    public static final String DELIVERY_METHOD = "deliveryMethod";
    public static final String DELIVERY_COMPANY_CODE = "deliveryCompanyCode";        // generic carrier code (master+listing)
    public static final String DELIVERY_CHARGE_TYPE = "deliveryChargeType";
    public static final String DELIVERY_CHARGE = "deliveryCharge";                   // numeric
    public static final String FREE_SHIP_OVER_AMOUNT = "freeShipOverAmount";         // numeric
    public static final String REMOTE_AREA_DELIVERABLE = "remoteAreaDeliverable";
    public static final String UNION_DELIVERY_TYPE = "unionDeliveryType";
    public static final String EXTRA_INFO_MESSAGE = "extraInfoMessage";

    /** Outbound place + return center (address block) — channel level only. Skipped in master resolution. */
    public static final Set<String> PLACE_KEYS = Set.of(
            OUTBOUND_SHIPPING_PLACE_CODE, RETURN_CENTER_CODE, RETURN_CHARGE_NAME, RETURN_CONTACT_NUMBER,
            RETURN_ZIP_CODE, RETURN_ADDRESS, RETURN_ADDRESS_DETAIL);

    /** Every override key = the listing (channel) whitelist. */
    public static final Set<String> LISTING_KEYS = Set.of(
            OUTBOUND_SHIPPING_PLACE_CODE, RETURN_CENTER_CODE, RETURN_CHARGE_NAME, RETURN_CONTACT_NUMBER,
            RETURN_ZIP_CODE, RETURN_ADDRESS, RETURN_ADDRESS_DETAIL,
            RETURN_CHARGE, DELIVERY_CHARGE_ON_RETURN, DELIVERY_METHOD, DELIVERY_COMPANY_CODE,
            DELIVERY_CHARGE_TYPE, DELIVERY_CHARGE, FREE_SHIP_OVER_AMOUNT, REMOTE_AREA_DELIVERABLE,
            UNION_DELIVERY_TYPE, EXTRA_INFO_MESSAGE);

    /** Master whitelist = listing whitelist minus the place keys (account-specific centers). */
    public static final Set<String> MASTER_KEYS = Set.of(
            RETURN_CHARGE, DELIVERY_CHARGE_ON_RETURN, DELIVERY_METHOD, DELIVERY_COMPANY_CODE,
            DELIVERY_CHARGE_TYPE, DELIVERY_CHARGE, FREE_SHIP_OVER_AMOUNT, REMOTE_AREA_DELIVERABLE,
            UNION_DELIVERY_TYPE, EXTRA_INFO_MESSAGE);

    /**
     * Filter a raw override map to the master whitelist (place keys silently dropped). Returns {@code null}
     * when the result is empty (= no override), so storage can persist {@code null} = inherit.
     * FEATURE_2610_13 / D104: the master map also carries the 11st master keys ({@code elevenst.*}).
     */
    public static Map<String, String> filterMaster(Map<String, String> raw) {
        return filter(raw, MASTER_KEYS, ElevenstShippingKeys.MASTER_KEYS);
    }

    /**
     * Filter a raw override map to the whitelist of a listing on {@code platform} (unknown keys dropped):
     * an 11st listing takes only the {@code elevenst.*} keys (FEATURE_2610_13 / D104), an ESM listing only the
     * {@code esm.*} keys (FEATURE_2610_26 / D36 · D40), every other listing the Coupang keys. {@code null} when empty.
     */
    public static Map<String, String> filterListing(Map<String, String> raw, Platform platform) {
        if (platform == Platform.ELEVENST) {
            return filter(raw, ElevenstShippingKeys.ALL_KEYS);
        }
        if (platform == Platform.ESM) {
            return filter(raw, EsmShippingKeys.ALL_KEYS);
        }
        return filter(raw, LISTING_KEYS);
    }

    @SafeVarargs
    private static Map<String, String> filter(Map<String, String> raw, Set<String>... allowedSets) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Map<String, String> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            for (Set<String> allowed : allowedSets) {
                if (allowed.contains(entry.getKey())) {
                    filtered.put(entry.getKey(), entry.getValue());
                    break;
                }
            }
        }
        return filtered.isEmpty() ? null : filtered;
    }
}
