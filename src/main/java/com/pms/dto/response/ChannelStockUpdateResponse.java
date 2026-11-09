package com.pms.dto.response;

import com.pms.service.listing.StockPushLine;

import java.util.List;

/**
 * Result of saving channel option stocks and sending them to the market (FEATURE_2611_19 / D7). The same shape as
 * {@link ChannelPriceUpdateResponse}, but {@code failed} means something else:
 * <ul>
 *   <li>stock — {@code failed} = <b>saved</b> in oclyx, the market refused (D6). The next [수정 요청] sends it again.</li>
 *   <li>price ({@link ChannelPriceUpdateResponse}) — {@code failed} = <b>not saved</b> (2609_19 D6).</li>
 * </ul>
 * On a platform without a stock push all three lists are empty / 0 and {@code listing.needsResync} keeps its old rule
 * (status != DRAFT).
 *
 * @param listing the full option set after the save
 * @param pushed  how many options the market took
 * @param skipped names of options saved only — no market number yet, or switched off (D25)
 * @param failed  options the market refused, with the market's own text — their stock is saved
 */
public record ChannelStockUpdateResponse(ListingOptionsResponse listing, int pushed, List<String> skipped,
                                         List<FailedOption> failed) {

    /** One option the market refused, with the platform's own message. */
    public record FailedOption(String optionName, String message) {
    }

    /** The saved option set + the push lines of that one listing. */
    public static ChannelStockUpdateResponse of(ListingOptionsResponse listing, List<StockPushLine> lines) {
        int pushed = (int) lines.stream().filter(l -> l.result() == StockPushLine.Result.PUSHED).count();
        List<String> skipped = lines.stream().filter(l -> l.result() == StockPushLine.Result.SKIPPED)
                .map(StockPushLine::optionName).toList();
        List<FailedOption> failed = lines.stream().filter(l -> l.result() == StockPushLine.Result.FAILED)
                .map(l -> new FailedOption(l.optionName(), l.message())).toList();
        return new ChannelStockUpdateResponse(listing, pushed, skipped, failed);
    }
}
