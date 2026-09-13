package com.pms.service;

import com.pms.domain.MarginPolicy;
import com.pms.domain.MasterProductOption;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.repository.MarginPolicyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Selling-price engine (margin reverse-calc) — FEATURE_2608_06 / 3b-2, rewired in / 13 and / 52.
 *
 * <p>Per option, given the option's cost sum (Σ product.price × quantity), its parent cell and its matched
 * master option:</p>
 * <pre>
 *   sellingPrice = (costSum + delivery + box) / (1 − commissionRate × (1 + feeVatRate) − marginRate)
 * </pre>
 * where commissionRate = {@code resolvePlatformCategory(cell).getCommissionRate()} (the mapped
 * {@link PlatformCategory} owns the commission — 52), delivery = {@code resolveDelivery(cell, option).cost},
 * box = {@code resolvePackage(cell, option).cost} (both from {@link MasterChannelConfigService} = option
 * override ?? master default), and marginRate = {@code MarginPolicy(seller, platform).marginRate}. The result
 * is rounded to the nearest 10 won ({@code setScale(-1, HALF_UP)}).
 *
 * <p>400 ({@link IllegalArgumentException}) when: category/mapping/delivery/box unset (raised by the
 * resolver), commission unset on the mapped PlatformCategory (seeding gap — no runtime tree fallback), margin
 * preset missing, or the denominator {@code (1 − commission × (1 + feeVat) − margin) ≤ 0}.</p>
 */
@Service
public class PriceCalculator {

    private final MarginPolicyRepository marginPolicyRepository;
    private final MasterChannelConfigService masterChannelConfigService;

    /**
     * VAT rate charged on top of the marketplace commission. Korean VAT 10% = {@code 0.1} (PLAN 2609_30 D19).
     * Configurable rather than a constant because a tax-exempt ({@code taxType}) exception is still unmeasured.
     * Must be within {@code [0, 1]}; {@code 0} = the legacy formula.
     */
    private final BigDecimal feeVatRate;

    public PriceCalculator(MarginPolicyRepository marginPolicyRepository,
                           MasterChannelConfigService masterChannelConfigService,
                           @Value("${oclyx.pricing.fee-vat-rate:0.1}") BigDecimal feeVatRate) {
        this.marginPolicyRepository = marginPolicyRepository;
        this.masterChannelConfigService = masterChannelConfigService;
        BigDecimal rate = feeVatRate == null ? BigDecimal.ZERO : feeVatRate;
        // Range check at startup (the @ConfigurationProperties equivalent of @DecimalMin("0.0") @DecimalMax("1.0")):
        // a typo such as 10 instead of 0.1 would silently flip every recommended price, so fail fast.
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("oclyx.pricing.fee-vat-rate 는 0 이상 1 이하여야 합니다: " + rate);
        }
        this.feeVatRate = rate;
    }

    /**
     * Compute the rounded selling price for one option of {@code cell}.
     *
     * @param cell         the channel listing (source of platform, seller, margin, master link)
     * @param masterOption the matched master option (delivery/box overrides); null = master defaults
     * @param costSum      Σ(product.price × quantity) for that option's BOM
     * @return selling price rounded to the nearest 10 won
     * @throws IllegalArgumentException (→400) on missing category/delivery/box/margin or denominator ≤ 0
     */
    public BigDecimal calculatePrice(ProductListing cell, MasterProductOption masterOption, BigDecimal costSum) {
        return calculatePrices(cell, masterOption, costSum).salePrice();
    }

    /**
     * Compute both the selling price and the display "original" (strike-through) price for one option (73).
     *
     * <p>{@code originalPrice = salePrice / (1 − displayDiscountRate)}, rounded to the nearest 10 won. The rate
     * is the seller×platform {@link MarginPolicy#getDisplayDiscountRate()} (null → 0, clamped to {@code [0, 0.5]}).
     * rate=0 → {@code originalPrice.equals(salePrice)} (no discount shown).</p>
     */
    public PriceResult calculatePrices(ProductListing cell, MasterProductOption masterOption, BigDecimal costSum) {
        return prices(resolveBasis(cell, masterOption), costSum);
    }

    /**
     * Resolve everything the reverse-calc needs that depends on the <b>cell only</b> (FEATURE_2609_39 / D16):
     * the mapped category's commission, the VAT-loaded effective commission, and the seller×platform margin
     * preset (target rate, display discount rate and the alert thresholds — they all live on the one preset row).
     *
     * <p>⚠️ Exposed so a caller that walks many options of the same cell can cache it. Resolving it per option
     * re-runs the category-mapping lookup and the margin-preset lookup for every single option — the exact N+1
     * {@code SalesStatsServiceImpl} avoids with its per-cell {@code commissionCache}.</p>
     *
     * @throws IllegalArgumentException (→400) when the category mapping / commission / margin preset is missing
     */
    public CellPricingBasis resolveCellBasis(ProductListing cell) {
        // Commission is owned by the mapped PlatformCategory (52). null = the category was not seeded with a
        // commission — a seeding gap, not a runtime fallback: 400. (Prefilled once at import/mapping time.)
        PlatformCategory platformCategory = masterChannelConfigService.resolvePlatformCategory(cell);
        BigDecimal commissionRate = platformCategory.getCommissionRate();
        if (commissionRate == null) {
            throw new IllegalArgumentException("수수료 미설정 — 카테고리 시드 필요");
        }

        MarginPolicy margin = marginPolicyRepository
                .findBySellerIdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElseThrow(() -> new IllegalArgumentException("마진 프리셋 없음"));

        // The commission is settled together with its own VAT (D19), so the reverse-calc must subtract both.
        BigDecimal effectiveCommission = commissionRate.multiply(BigDecimal.ONE.add(feeVatRate));
        return new CellPricingBasis(commissionRate, effectiveCommission, margin.getMarginRate(),
                margin.getDisplayDiscountRate(), margin.getMinMarginAmount(), margin.getMinMarginRate());
    }

    /**
     * Add the per-option part (delivery + box = option override ?? master default) to an already resolved
     * {@link CellPricingBasis}. Kept separate from {@link #resolveCellBasis} because the shipping/box lookup
     * genuinely varies per master option while the commission and the margin preset do not.
     */
    public PricingBasis resolveBasis(CellPricingBasis cellBasis, ProductListing cell, MasterProductOption masterOption) {
        BigDecimal delivery = masterChannelConfigService.resolveDelivery(cell, masterOption).getCost();
        BigDecimal box = masterChannelConfigService.resolvePackage(cell, masterOption).getCost();
        return new PricingBasis(cellBasis, delivery, box);
    }

    /** Full resolution for a single option — the one-shot path used by {@link #calculatePrices}. */
    public PricingBasis resolveBasis(ProductListing cell, MasterProductOption masterOption) {
        return resolveBasis(resolveCellBasis(cell), cell, masterOption);
    }

    /**
     * The reverse-calc itself, over an already resolved basis. This is the <b>single owner of the formula</b>:
     * {@code salePrice = (costSum + delivery + box) / (1 − commission × (1 + feeVat) − margin)}.
     *
     * @throws IllegalArgumentException (→400) when the denominator is ≤ 0
     */
    public PriceResult prices(PricingBasis basis, BigDecimal costSum) {
        BigDecimal denominator = BigDecimal.ONE
                .subtract(basis.effectiveCommissionRate())
                .subtract(basis.targetMarginRate());
        if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
            // The effective rate is in the message because the VAT shrinks the denominator: a combination that
            // used to pass can now trip this guard, and the raw commission alone would not explain why.
            throw new IllegalArgumentException("수수료+마진이 100% 이상 — 실효 수수료율 " + basis.effectiveCommissionRate()
                    + "(수수료 " + basis.commissionRate() + " × VAT " + feeVatRate + " 포함) + 마진 "
                    + basis.targetMarginRate());
        }

        BigDecimal numerator = costSum.add(basis.delivery()).add(basis.box());
        // Divide with headroom, then round to the nearest 10 won; normalize scale to 2 for the DECIMAL(10,2) column.
        BigDecimal salePrice = numerator.divide(denominator, 4, RoundingMode.HALF_UP)
                .setScale(-1, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
        return new PriceResult(salePrice, originalPrice(salePrice, basis.displayDiscountRate()));
    }

    /**
     * Split the margin of a price that is <b>already live</b> (PLAN 2609_39 / D3) — the reverse of
     * {@link #prices}. Persists nothing, sends nothing.
     *
     * <pre>
     *   비용합   = costSum + delivery + box
     *   수수료   = judgedPrice × commissionRate × (1 + feeVatRate)
     *   마진액   = judgedPrice − 비용합 − 수수료
     *   마진율   = 마진액 ÷ judgedPrice
     * </pre>
     *
     * <p>It lives next to {@link #prices} on purpose: writing the same interpretation twice is how two screens
     * start showing different numbers for the same option.</p>
     *
     * @param judgedPrice the price the margin is judged on — must be &gt; 0 (the caller decides what to do with
     *                    a zero/negative price; here it would make the ratio undefined)
     */
    public CostBreakdown breakdown(PricingBasis basis, BigDecimal costSum, BigDecimal judgedPrice) {
        if (judgedPrice == null || judgedPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("판정 가격이 0 이하라 마진을 계산할 수 없습니다");
        }
        BigDecimal costTotal = costSum.add(basis.delivery()).add(basis.box());
        BigDecimal feeAmount = judgedPrice.multiply(basis.effectiveCommissionRate())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal marginAmount = judgedPrice.subtract(costTotal).subtract(feeAmount)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal marginRate = marginAmount.divide(judgedPrice, 4, RoundingMode.HALF_UP);
        return new CostBreakdown(costSum, basis.delivery(), basis.box(), basis.commissionRate(),
                basis.effectiveCommissionRate(), basis.targetMarginRate(), costTotal, feeAmount,
                marginAmount, marginRate);
    }

    /**
     * Display (strike-through) original price that goes with a <b>manually set</b> sale price
     * (FEATURE_2609_19 / D7). The register / [수정 요청] payload always carries {@code originalPrice}, so a
     * manual price that is saved without refreshing it would leave a "sale price > original price" inversion
     * on the market. The margin preset lookup lives here so callers don't duplicate it.
     *
     * @param cell      the channel cell (source of seller + platform for the preset)
     * @param salePrice the sale price to derive the display price from
     * @throws IllegalArgumentException (→400) when the seller×platform margin preset is missing
     */
    public BigDecimal displayOriginalPrice(ProductListing cell, BigDecimal salePrice) {
        MarginPolicy margin = marginPolicyRepository
                .findBySellerIdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElseThrow(() -> new IllegalArgumentException("마진 프리셋 없음"));
        return originalPrice(salePrice, margin.getDisplayDiscountRate());
    }

    /**
     * Reverse-calc the display original price from the sale price and the display discount rate. rate is
     * clamped to {@code [0, 0.5]} (null → 0); the denominator {@code (1 − rate)} is therefore always in
     * {@code [0.5, 1]} (never ≤ 0). rate=0 returns a value equal to {@code salePrice} (no discount shown).
     */
    private static BigDecimal originalPrice(BigDecimal salePrice, BigDecimal displayDiscountRate) {
        BigDecimal rate = displayDiscountRate == null ? BigDecimal.ZERO : displayDiscountRate;
        if (rate.compareTo(BigDecimal.ZERO) < 0) {
            rate = BigDecimal.ZERO;
        } else if (rate.compareTo(RATE_CAP) > 0) {
            rate = RATE_CAP;
        }
        BigDecimal denom = BigDecimal.ONE.subtract(rate);
        return salePrice.divide(denom, 4, RoundingMode.HALF_UP)
                .setScale(-1, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static final BigDecimal RATE_CAP = new BigDecimal("0.5");

    /** Selling price + display original (strike-through) price for one option (73). */
    public record PriceResult(BigDecimal salePrice, BigDecimal originalPrice) {
    }

    /**
     * The cell-scoped half of the pricing inputs (FEATURE_2609_39 / D16). Cache this per cell when walking
     * many options. {@code minMarginAmount}/{@code minMarginRate} ride along because they come from the very
     * same {@link MarginPolicy} row — looking them up separately would double the preset queries (D4).
     */
    public record CellPricingBasis(BigDecimal commissionRate, BigDecimal effectiveCommissionRate,
                                   BigDecimal targetMarginRate, BigDecimal displayDiscountRate,
                                   BigDecimal minMarginAmount, BigDecimal minMarginRate) {
    }

    /** A {@link CellPricingBasis} plus the per-option delivery and box costs. */
    public record PricingBasis(CellPricingBasis cellBasis, BigDecimal delivery, BigDecimal box) {

        public BigDecimal commissionRate() {
            return cellBasis.commissionRate();
        }

        public BigDecimal effectiveCommissionRate() {
            return cellBasis.effectiveCommissionRate();
        }

        public BigDecimal targetMarginRate() {
            return cellBasis.targetMarginRate();
        }

        public BigDecimal displayDiscountRate() {
            return cellBasis.displayDiscountRate();
        }
    }

    /**
     * The margin of one option at a given price, split into its parts (FEATURE_2609_39 / D3). Read-only:
     * nothing here is stored or sent.
     */
    public record CostBreakdown(BigDecimal costSum, BigDecimal delivery, BigDecimal box,
                                BigDecimal commissionRate, BigDecimal effectiveCommissionRate,
                                BigDecimal targetMarginRate, BigDecimal costTotal, BigDecimal feeAmount,
                                BigDecimal marginAmount, BigDecimal marginRate) {
    }
}
