package com.pms.service.listing;

import com.pms.domain.GrandTotalSend;
import com.pms.domain.PlatformCategory;

import java.util.Map;

/**
 * What a market push reported back (FEATURE_2610_34). The adapter only reports — the orchestration stores it.
 *
 * @param platformProductId        the market product id (register) · {@code null} (update)
 * @param grandTotalSend           the listing's new 「총 수량」 state · {@code null} = keep the stored one
 * @param learnedCategory          the Coupang category whose memory changes · {@code null} = no change (D17)
 * @param learnedGrandTotalOmitted the new memory value of {@code learnedCategory} (D2 = true · D8 = false)
 * @param marketBundleType         FEATURE_2610_36 / D10 ① · D11: the Coupang {@code bundleInfo.bundleType} the push
 *                                 sent ({@code SINGLE} · {@code AB}) · {@code null} = keep the stored one
 * @param skipped                  FEATURE_2611_25 / D26: true = nothing was sent (a batch push found every item equal
 *                                 to Coupang) — the orchestration only clears {@code needsMarketSync}
 * @param optionActive             FEATURE_2611_25 / D31 · R12: product listing option id → the on/off to store for
 *                                 that option · an empty map = change nothing
 */
public record PushOutcome(String platformProductId, GrandTotalSend grandTotalSend,
                          PlatformCategory learnedCategory, boolean learnedGrandTotalOmitted,
                          String marketBundleType, boolean skipped, Map<Long, Boolean> optionActive) {

    /** The 5-argument form used before FEATURE_2611_25 — {@code skipped = false} · {@code optionActive} empty. */
    public PushOutcome(String platformProductId, GrandTotalSend grandTotalSend,
                       PlatformCategory learnedCategory, boolean learnedGrandTotalOmitted,
                       String marketBundleType) {
        this(platformProductId, grandTotalSend, learnedCategory, learnedGrandTotalOmitted, marketBundleType,
                false, Map.of());
    }

    /** The 4-argument form used before FEATURE_2610_36 — {@code marketBundleType = null}. */
    public PushOutcome(String platformProductId, GrandTotalSend grandTotalSend,
                       PlatformCategory learnedCategory, boolean learnedGrandTotalOmitted) {
        this(platformProductId, grandTotalSend, learnedCategory, learnedGrandTotalOmitted, null);
    }

    /** Nothing to store — every non-Coupang adapter (FEATURE_2610_36: a Coupang push reports its bundle type). */
    public static PushOutcome unchanged(String platformProductId) {
        return new PushOutcome(platformProductId, null, null, false);
    }

    /** FEATURE_2611_25 / D26: a batch push that sent nothing — every stored state is kept. */
    public static PushOutcome notSent() {
        return new PushOutcome(null, null, null, false, null, true, Map.of());
    }
}
