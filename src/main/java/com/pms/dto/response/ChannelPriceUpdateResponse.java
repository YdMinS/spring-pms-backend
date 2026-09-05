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
 *                (not approved / DRAFT cell, D5)
 * @param failed  options the market rejected — those are <b>not saved</b> (D6); the message is Coupang's own
 */
public record ChannelPriceUpdateResponse(ListingOptionsResponse listing, int pushed,
                                         List<String> skipped, List<FailedOption> failed) {

    /** One option the market refused, with the platform's own message. */
    public record FailedOption(String optionName, String message) {
    }
}
