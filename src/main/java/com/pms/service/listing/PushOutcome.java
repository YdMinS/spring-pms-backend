package com.pms.service.listing;

import com.pms.domain.GrandTotalSend;
import com.pms.domain.PlatformCategory;

/**
 * What a market push reported back (FEATURE_2610_34). The adapter only reports — the orchestration stores it.
 *
 * @param platformProductId        the market product id (register) · {@code null} (update)
 * @param grandTotalSend           the listing's new 총 수량 state · {@code null} = keep the stored one
 * @param learnedCategory          the Coupang category whose memory changes · {@code null} = no change (D17)
 * @param learnedGrandTotalOmitted the new memory value of {@code learnedCategory} (D2 = true · D8 = false)
 */
public record PushOutcome(String platformProductId, GrandTotalSend grandTotalSend,
                          PlatformCategory learnedCategory, boolean learnedGrandTotalOmitted) {

    /** Nothing about 총 수량 to store — every non-Coupang adapter, and a Coupang push that changed nothing. */
    public static PushOutcome unchanged(String platformProductId) {
        return new PushOutcome(platformProductId, null, null, false);
    }
}
