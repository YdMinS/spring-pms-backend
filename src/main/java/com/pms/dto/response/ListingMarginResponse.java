package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Margin view of one listing (FEATURE_2610_27 / D1 · D2 · D3 · D11 · D12 · D26; FEATURE_2610_28 / D7 · D12 · D20 ·
 * D22). Read only — nothing is stored or sent.
 *
 * @param listingId the listing
 * @param platform  the listing's platform
 * @param options   every option of the listing, active or not, ordered by option id
 */
public record ListingMarginResponse(Long listingId, String platform, List<OptionMargin> options) {

    /**
     * One option.
     *
     * @param sites one entry per site the option is sold on — one on a Coupang or 11st listing, Gmarket then Auction on
     *              an ESM listing (FEATURE_2610_28 / D20 · D21)
     */
    public record OptionMargin(Long optionId, String optionName, List<SiteMargin> sites) {
    }

    /**
     * The margin of one price on one site (FEATURE_2610_27 / D2 · D5 · D8 · D11 · D12). The parts come from
     * {@code PriceCalculator.breakdown} and {@code breakEvenPrice}; when the margin cannot be calculated every amount
     * is null, {@code components} is empty and {@code reason} says why.
     *
     * @param site                  {@code COUPANG} · {@code ELEVENST} · {@code GMARKET} · {@code AUCTION} — the
     *                              platform name, or the ESM site (FEATURE_2610_28 / D21)
     * @param marketPriceAt         when the market price was last confirmed — a KST wall clock, null when unknown or
     *                              on a preview (FEATURE_2610_27 / D26 · D32; FEATURE_2610_28 / D22)
     * @param marketPrice           the price live on the market; null = unknown
     * @param systemPrice           the system selling price
     * @param judgedPrice           the price the margin is judged on = {@code marketPrice ?? systemPrice} (D2); for a
     *                              preview = the price typed in (D6)
     * @param marketPriceMissing    true when {@code marketPrice} is null and the system price was used (D2)
     * @param costSum               Σ component cost — a component without a unit price adds nothing
     * @param components            the components behind {@code costSum}, in BOM order (FEATURE_2610_28 / D7 · D14 ·
     *                              D15)
     * @param delivery              delivery cost
     * @param box                   box cost
     * @param commissionRate        the sale commission rate with VAT = commission × (1 + fee VAT) (FEATURE_2610_28 /
     *                              D12)
     * @param commissionAmount      the sale commission in won = {@code feeAmount − shippingFeeCommission}
     *                              (FEATURE_2610_28 / D12)
     * @param shippingFeeCommission the 11st · ESM delivery-fee commission in won; 0 on every other site
     *                              (FEATURE_2610_28 / D12)
     * @param feeAmount             marketplace fee = {@code commissionAmount + shippingFeeCommission}
     * @param marginAmount          margin in won
     * @param marginRate            margin ÷ judged price (0.1234 = 12.34%)
     * @param breakEvenPrice        the price at which the margin is 0
     * @param minMarginAmount       the margin preset's minimum margin amount; null = unset (FEATURE_2610_28 / D13)
     * @param minMarginRate         the margin preset's minimum margin rate; null = unset (FEATURE_2610_28 / D13)
     * @param below                 under the preset's minimum margin amount or rate (D5); false when it cannot be
     *                              judged
     * @param reason                why the margin cannot be calculated; null when it was (D12)
     */
    public record SiteMargin(String site, LocalDateTime marketPriceAt, BigDecimal marketPrice, BigDecimal systemPrice,
                             BigDecimal judgedPrice, boolean marketPriceMissing, BigDecimal costSum,
                             List<CostComponent> components, BigDecimal delivery, BigDecimal box,
                             BigDecimal commissionRate, BigDecimal commissionAmount, BigDecimal shippingFeeCommission,
                             BigDecimal feeAmount, BigDecimal marginAmount, BigDecimal marginRate,
                             BigDecimal breakEvenPrice, BigDecimal minMarginAmount, BigDecimal minMarginRate,
                             boolean below, String reason) {
    }

    /**
     * One component of {@code costSum} (FEATURE_2610_28 / D7 · D14).
     *
     * @param productName the product name
     * @param quantity    how many go into the option
     * @param unitPrice   the product's unit price; null = not set (shown as 「단가 없음」, D14)
     * @param amount      {@code unitPrice × quantity}; null when {@code unitPrice} is null
     */
    public record CostComponent(String productName, int quantity, BigDecimal unitPrice, BigDecimal amount) {
    }
}
