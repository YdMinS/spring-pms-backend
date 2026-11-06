package com.pms.service.listing.shipping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The LotteON shipping keys (FEATURE_2610_24 / D42 · D65) — the single vocabulary shared by the channel layer
 * ({@code LotteonShippingConfig.settings}) and the LotteON listing shipping override. Every key starts with
 * {@link #PREFIX}.
 *
 * <ul>
 *   <li>{@link #INPUT_KEYS} — the three numbers a person picks (D42), in screen order.</li>
 *   <li>{@link #POLICY_KEYS} — the facts of the chosen shipping fee policy, written by the server from 166 when the
 *       settings are saved (D65); whatever a client sends under these keys is dropped.</li>
 *   <li>{@link #FEE_GROUP} — the policy number + its facts: one unit that a listing takes whole from the layer that
 *       picked the policy (D65).</li>
 * </ul>
 * All keys are channel · listing only — there is no master layer (D42).
 */
public final class LotteonShippingKeys {

    public static final String PREFIX = "lotteon.";

    /** D42: outbound place number (150 {@code dvpNo}, type 02). */
    public static final String OWHP_NO = "lotteon.owhpNo";
    /** D42: return place number (150 {@code dvpNo}, type 01). */
    public static final String RTRP_NO = "lotteon.rtrpNo";
    /** D42: shipping fee policy number (166 {@code dvCstPolNo}). */
    public static final String DV_CST_POL_NO = "lotteon.dvCstPolNo";

    /** D65: the policy's {@code dvCstDvsCd} — A paid · B free · C conditional. */
    public static final String DV_CST_DVS_CD = "lotteon.dvCstDvsCd";
    /** D65: the policy's {@code dvCst}. */
    public static final String DV_CST = "lotteon.dvCst";
    /** D65: the policy's {@code cndlFreeStdAmt} — set when LotteON gives one. */
    public static final String CNDL_FREE_STD_AMT = "lotteon.cndlFreeStdAmt";

    public static final Set<String> INPUT_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            OWHP_NO, RTRP_NO, DV_CST_POL_NO)));

    public static final Set<String> POLICY_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            DV_CST_DVS_CD, DV_CST, CNDL_FREE_STD_AMT)));

    public static final Set<String> FEE_GROUP = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            DV_CST_POL_NO, DV_CST_DVS_CD, DV_CST, CNDL_FREE_STD_AMT)));

    public static final Set<String> ALL_KEYS;

    static {
        Set<String> all = new LinkedHashSet<>(INPUT_KEYS);
        all.addAll(POLICY_KEYS);
        ALL_KEYS = Collections.unmodifiableSet(all);
    }

    /** D42: screen labels of the three input keys, used in the "missing field" message (register guard). */
    public static final Map<String, String> LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(OWHP_NO, "출고지");
        labels.put(RTRP_NO, "회수지");
        labels.put(DV_CST_POL_NO, "배송비 정책");
        LABELS = Collections.unmodifiableMap(labels);
    }

    private LotteonShippingKeys() {
    }
}
