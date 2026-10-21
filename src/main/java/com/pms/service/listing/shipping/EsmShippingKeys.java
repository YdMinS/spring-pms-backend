package com.pms.service.listing.shipping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ESM shipping keys (FEATURE_2610_26 / D36 · D60 · D66) — the single vocabulary shared by the channel layer
 * ({@code EsmShippingConfig.settings}) and the ESM listing shipping override. Every key starts with {@link #PREFIX}.
 *
 * <ul>
 *   <li>{@link #INPUT_KEYS} — the six values a person picks or types (D36), in screen order.</li>
 *   <li>{@link #POLICY_KEYS} — the four facts of the chosen bundle-fee policy, written by the server from post 18
 *       when the settings are saved (D60 · D66); whatever a client sends under these keys is dropped.</li>
 *   <li>{@link #BUNDLE_GROUP} — shipping place + bundle-fee policy + its four facts: one unit that a listing takes
 *       whole from one layer (D66).</li>
 * </ul>
 * All keys are channel · listing only — there is no master layer (D36).
 */
public final class EsmShippingKeys {

    public static final String PREFIX = "esm.";

    public static final String PLACE_NO = "esm.placeNo";
    public static final String BUNDLE_POLICY_NO = "esm.bundlePolicyNo";
    public static final String DISPATCH_POLICY_NO = "esm.dispatchPolicyNo";
    public static final String RETURN_ADDR_NO = "esm.returnAddrNo";
    public static final String COMPANY_NO = "esm.companyNo";
    public static final String RETURN_FEE = "esm.returnFee";

    /** D60: 1 free · 2 paid · 3 conditional (post 18 {@code feeType}). */
    public static final String BUNDLE_FEE_TYPE = "esm.bundleFeeType";
    /** D60: the policy's {@code fee}. */
    public static final String BUNDLE_FEE = "esm.bundleFee";
    /** D60: the policy's {@code shippingFee[0].condition} — set for a conditional policy only. */
    public static final String BUNDLE_FREE_OVER = "esm.bundleFreeOver";
    /** D60: the policy's {@code isPrepayment} ("true" / "false"). */
    public static final String BUNDLE_PREPAY = "esm.bundlePrepay";

    public static final Set<String> INPUT_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            PLACE_NO, BUNDLE_POLICY_NO, DISPATCH_POLICY_NO, RETURN_ADDR_NO, COMPANY_NO, RETURN_FEE)));

    public static final Set<String> POLICY_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            BUNDLE_FEE_TYPE, BUNDLE_FEE, BUNDLE_FREE_OVER, BUNDLE_PREPAY)));

    public static final Set<String> BUNDLE_GROUP = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            PLACE_NO, BUNDLE_POLICY_NO, BUNDLE_FEE_TYPE, BUNDLE_FEE, BUNDLE_FREE_OVER, BUNDLE_PREPAY)));

    public static final Set<String> ALL_KEYS;

    static {
        Set<String> all = new LinkedHashSet<>(INPUT_KEYS);
        all.addAll(POLICY_KEYS);
        ALL_KEYS = Collections.unmodifiableSet(all);
    }

    /** D36: screen labels of the six input keys, used in the "missing field" message (register guard). */
    public static final Map<String, String> LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(PLACE_NO, "출하지");
        labels.put(BUNDLE_POLICY_NO, "묶음배송비");
        labels.put(DISPATCH_POLICY_NO, "발송정책");
        labels.put(RETURN_ADDR_NO, "반품·교환지");
        labels.put(COMPANY_NO, "택배사");
        labels.put(RETURN_FEE, "반품 배송비");
        LABELS = Collections.unmodifiableMap(labels);
    }

    private EsmShippingKeys() {
    }
}
