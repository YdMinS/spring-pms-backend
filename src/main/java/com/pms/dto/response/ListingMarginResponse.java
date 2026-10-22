package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Margin view of one listing (FEATURE_2610_27 / D1 · D2 · D3 · D11 · D12 · D26). Read only — nothing is stored or sent.
 *
 * @param listingId the listing
 * @param platform  the listing's platform
 * @param options   every option of the listing, active or not, ordered by option id
 */
public record ListingMarginResponse(Long listingId, String platform, List<OptionMargin> options) {

    /**
     * One option.
     *
     * @param marketPriceAt when the market price was last confirmed — a KST wall clock string, null when unknown (D26 ·
     *                      D32 · D35)
     * @param margin        the margin of {@code sellingPrice} — the Gmarket one on an ESM option (D3)
     * @param auctionMargin the Auction margin of an ESM option; null on every other platform (D3)
     */
    public record OptionMargin(Long optionId, String optionName, LocalDateTime marketPriceAt, SiteMargin margin,
                               SiteMargin auctionMargin) {
    }

    /**
     * The margin of one price (D2 · D5 · D8 · D11 · D12). The parts come from {@code PriceCalculator.breakdown} and
     * {@code breakEvenPrice}; when the margin cannot be calculated every amount is null and {@code reason} says why.
     *
     * @param marketPrice        the price live on the market; null = unknown
     * @param systemPrice        the system selling price
     * @param judgedPrice        the price the margin is judged on = {@code marketPrice ?? systemPrice} (D2); for a
     *                           preview = the price typed in (D6)
     * @param marketPriceMissing true when {@code marketPrice} is null and the system price was used (D2)
     * @param costSum            Σ component cost
     * @param delivery           delivery cost
     * @param box                box cost
     * @param feeAmount          marketplace fee (commission with VAT + 11st · ESM delivery-fee commission)
     * @param marginAmount       margin in won
     * @param marginRate         margin ÷ judged price (0.1234 = 12.34%)
     * @param breakEvenPrice     the price at which the margin is 0
     * @param below              under the preset's minimum margin amount or rate (D5); false when it cannot be judged
     * @param reason             why the margin cannot be calculated; null when it was (D12)
     */
    public record SiteMargin(BigDecimal marketPrice, BigDecimal systemPrice, BigDecimal judgedPrice,
                             boolean marketPriceMissing, BigDecimal costSum, BigDecimal delivery, BigDecimal box,
                             BigDecimal feeAmount, BigDecimal marginAmount, BigDecimal marginRate,
                             BigDecimal breakEvenPrice, boolean below, String reason) {
    }
}
