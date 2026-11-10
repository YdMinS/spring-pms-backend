package com.pms.dto.response;

import java.util.List;

/**
 * Result of saving channel option prices and pushing them to the market (FEATURE_2609_19 / D5·D6). A partial
 * success is the normal path — the screen redraws its values from {@code listing} and shows skipped/failed
 * as they are.
 *
 * @param listing the full option set after the save. ⚠️ Not named {@code options} because it already
 *                contains an {@code options} array of its own (D14).
 * @param pushed  how many options actually reached the market
 * @param skipped names of options saved locally only, because they carry no market identifier yet
 *                (not approved / DRAFT cell, D5), because a whole-product market (11st · ESM) does not have every
 *                active option yet, or because they are switched off (FEATURE_2611_19 / D18 · D25)
 * @param failed  options the market rejected — those are <b>not saved</b> (D6); the message is the market's own. A
 *                whole-product market (11st · ESM) never fills it: its refusal fails the request (FEATURE_2611_19 /
 *                D18). The stock save reads {@code failed} the other way round — see {@link ChannelStockUpdateResponse}
 */
public record ChannelPriceUpdateResponse(ListingOptionsResponse listing, int pushed,
                                         List<String> skipped, List<FailedOption> failed) {

    /** One option the market refused, with the platform's own message. */
    public record FailedOption(String optionName, String message) {
    }
}
