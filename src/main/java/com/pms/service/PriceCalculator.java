package com.pms.service;

import com.pms.domain.MarginPolicy;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Platform;
import com.pms.domain.PlatformCategory;
import com.pms.domain.ProductListing;
import com.pms.repository.MarginPolicyRepository;
import com.pms.service.listing.shipping.ElevenstShippingResolver;
import com.pms.service.listing.shipping.EsmShippingResolver;
import org.springframework.beans.factory.annotation.Autowired;
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
 *   sellingPrice = (costSum + delivery + box + shippingFeeCost) / (1 − commissionRate × (1 + feeVatRate) − marginRate)
 * </pre>
 * shippingFeeCost = 3.3% of the buyer's one-unit shipping fee on an 11st cell (FEATURE_2610_13 / D16 · D92 · D111)
 * or of the chosen bundle-fee policy's prepaid fee on an ESM cell (FEATURE_2610_26 / D60), 0 on every other cell
 * ({@link CustomerShippingFee});
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

    /**
     * FEATURE_2610_13 / D16: an 11st cell's resolved shipping settings → the buyer's shipping fee the 3.3%
     * delivery-fee commission applies to. Null only on the 3-argument test-only constructor (= no 11st cells
     * priced).
     */
    private final ElevenstShippingResolver elevenstShippingResolver;

    /**
     * FEATURE_2610_26 / D60: an ESM cell's resolved shipping settings → the chosen bundle-fee policy's prepaid fee the
     * 3.3% applies to. Null on the test-only constructors (= no ESM cells priced).
     */
    private final EsmShippingResolver esmShippingResolver;

    /** Test-only constructor (no 11st / ESM shipping) — the Spring bean uses the 5-argument one. */
    public PriceCalculator(MarginPolicyRepository marginPolicyRepository,
                           MasterChannelConfigService masterChannelConfigService,
                           BigDecimal feeVatRate) {
        this(marginPolicyRepository, masterChannelConfigService, feeVatRate, null, null);
    }

    /** Test-only constructor (11st shipping, no ESM shipping). */
    public PriceCalculator(MarginPolicyRepository marginPolicyRepository,
                           MasterChannelConfigService masterChannelConfigService,
                           BigDecimal feeVatRate,
                           ElevenstShippingResolver elevenstShippingResolver) {
        this(marginPolicyRepository, masterChannelConfigService, feeVatRate, elevenstShippingResolver, null);
    }

    @Autowired
    public PriceCalculator(MarginPolicyRepository marginPolicyRepository,
                           MasterChannelConfigService masterChannelConfigService,
                           @Value("${oclyx.pricing.fee-vat-rate:0.1}") BigDecimal feeVatRate,
                           ElevenstShippingResolver elevenstShippingResolver,
                           EsmShippingResolver esmShippingResolver) {
        this.marginPolicyRepository = marginPolicyRepository;
        this.masterChannelConfigService = masterChannelConfigService;
        this.elevenstShippingResolver = elevenstShippingResolver;
        this.esmShippingResolver = esmShippingResolver;
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
                margin.getDisplayDiscountRate(), margin.getMinMarginAmount(), margin.getMinMarginRate(),
                customerShippingFee(cell));
    }

    /**
     * FEATURE_2610_26 / D52 · D58: the Auction selling price of one ESM option — {@link #prices} with the mapped
     * category's Auction commission and the preset's Auction target margin; cost, delivery, box and the buyer
     * shipping fee are the Gmarket ones. Only the sale price: the display original price is computed from the
     * Gmarket price alone (D52).
     *
     * @throws IllegalArgumentException (→400) on the same gaps as {@link #calculatePrices}, plus an unset Auction
     *         commission or Auction target margin
     */
    public BigDecimal calculateAuctionPrice(ProductListing cell, MasterProductOption masterOption,
                                            BigDecimal costSum) {
        return prices(resolveBasis(resolveAuctionCellBasis(cell), cell, masterOption), costSum).salePrice();
    }

    /**
     * D52: {@link #resolveCellBasis} for the Auction price — {@code auctionCommissionRate} of the mapped category and
     * {@code auctionMarginRate} of the seller×platform preset. Both unset → 400, never a fallback to the Gmarket value.
     */
    public CellPricingBasis resolveAuctionCellBasis(ProductListing cell) {
        PlatformCategory platformCategory = masterChannelConfigService.resolvePlatformCategory(cell);
        BigDecimal commissionRate = platformCategory.getAuctionCommissionRate();
        if (commissionRate == null) {
            throw new IllegalArgumentException("옥션 수수료 미설정 — 카테고리 시드 필요");
        }
        MarginPolicy margin = marginPolicyRepository
                .findBySellerIdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElseThrow(() -> new IllegalArgumentException("마진 프리셋 없음"));
        if (margin.getAuctionMarginRate() == null) {
            throw new IllegalArgumentException("옥션 목표 마진율 미설정");
        }
        BigDecimal effectiveCommission = commissionRate.multiply(BigDecimal.ONE.add(feeVatRate));
        return new CellPricingBasis(commissionRate, effectiveCommission, margin.getAuctionMarginRate(),
                margin.getDisplayDiscountRate(), margin.getMinMarginAmount(), margin.getMinMarginRate(),
                customerShippingFee(cell));
    }

    /**
     * FEATURE_2610_13 / D16 · D92 · D111: 11st cells; FEATURE_2610_26 / D60: ESM cells (both sites, one fee). Every
     * other platform pays no delivery-fee commission.
     */
    private CustomerShippingFee customerShippingFee(ProductListing cell) {
        if (cell.getPlatform() == Platform.ESM && esmShippingResolver != null) {
            return EsmShippingResolver.customerFee(esmShippingResolver.resolve(cell));
        }
        if (cell.getPlatform() != Platform.ELEVENST || elevenstShippingResolver == null) {
            return CustomerShippingFee.NONE;
        }
        return ElevenstShippingResolver.customerFee(elevenstShippingResolver.resolve(cell));
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
     * {@code salePrice = (costSum + delivery + box + shippingFeeCost) / (1 − commission × (1 + feeVat) − margin)}.
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
        BigDecimal salePrice = round10(numerator.divide(denominator, 4, RoundingMode.HALF_UP));
        // FEATURE_2610_13 / D16 · D92 · FEATURE_2610_26 / D60: the 11st / ESM delivery-fee commission joins the cost
        // side. The 「조건부 무료」 test is made ONCE on the price before this cost (re-judging would flip near the
        // threshold).
        BigDecimal shippingFeeCost = basis.customerShippingFee().costAt(salePrice);
        if (shippingFeeCost.signum() != 0) {
            salePrice = round10(numerator.add(shippingFeeCost).divide(denominator, 4, RoundingMode.HALF_UP));
        }
        return new PriceResult(salePrice, originalPrice(salePrice, basis.displayDiscountRate()));
    }

    /**
     * Break-even selling price (FEATURE_2609_44 / PLAN D1·D2) — the price at which the margin is exactly zero:
     * it covers cost + delivery + box and the channel commission (VAT included) and leaves nothing.
     * <b>Selling below this loses money.</b>
     *
     * <p>Same formula as {@link #prices}, <b>only the margin rate is 0</b>:
     * {@code (costSum + delivery + box + shippingFeeCost) / (1 − effectiveCommissionRate)}. Deriving it anywhere else (e.g. from
     * the amounts already on screen) would drift, because the screen has no rate and the amounts are rounded.</p>
     *
     * <p>Unlike {@link #prices} this <b>returns {@code null} instead of throwing</b>: it is one extra column on
     * a list, so an unusable input must not fail the row (the caller marks the row UNCALCULABLE on its own).</p>
     *
     * @return {@code null} when the input is missing or the denominator is ≤ 0 (commission ≥ 100%)
     */
    public BigDecimal breakEvenPrice(PricingBasis basis, BigDecimal costSum) {
        if (basis == null || costSum == null || basis.effectiveCommissionRate() == null
                || basis.delivery() == null || basis.box() == null) {
            return null;
        }
        // A commission of 100%+ (bad seed data) would divide by zero or hand back a negative price.
        BigDecimal denominator = BigDecimal.ONE.subtract(basis.effectiveCommissionRate());
        if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        // Rounding kept identical to prices(): nearest 10 won, scale 2 — the two must be comparable on screen.
        BigDecimal numerator = costSum.add(basis.delivery()).add(basis.box());
        BigDecimal breakEven = round10(numerator.divide(denominator, 4, RoundingMode.HALF_UP));
        // FEATURE_2610_13 / D112 · FEATURE_2610_26 / D60: the 11st / ESM delivery-fee commission is a cost here too
        // (same one-time judgement).
        BigDecimal shippingFeeCost = basis.customerShippingFee().costAt(breakEven);
        if (shippingFeeCost.signum() != 0) {
            breakEven = round10(numerator.add(shippingFeeCost).divide(denominator, 4, RoundingMode.HALF_UP));
        }
        return breakEven;
    }

    /** Nearest 10 won, then scale 2 for the DECIMAL(10,2) columns — the single rounding rule. */
    private static BigDecimal round10(BigDecimal value) {
        return value.setScale(-1, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Split the margin of a given price — one that is <b>already live</b> (PLAN 2609_39 / D3) or one typed in the
     * [가격 설정] window (FEATURE_2610_27 / D20) — the reverse of {@link #prices}. Persists nothing, sends nothing.
     *
     * <pre>
     *   costTotal    = costSum + delivery + box
     *   feeAmount    = judgedPrice × commissionRate × (1 + feeVatRate) + shippingFeeCost(judgedPrice)
     *   marginAmount = judgedPrice − costTotal − feeAmount
     *   marginRate   = marginAmount ÷ judgedPrice
     * </pre>
     * shippingFeeCost = the 11st / ESM delivery-fee commission (FEATURE_2610_13 / D112 · FEATURE_2610_26 / D60), 0 on
     * every other cell.
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
        // FEATURE_2610_13 / D112 · FEATURE_2610_26 / D60: the 11st / ESM delivery-fee commission is a marketplace fee
        // → it joins feeAmount.
        BigDecimal feeAmount = judgedPrice.multiply(basis.effectiveCommissionRate())
                .add(basis.customerShippingFee().costAt(judgedPrice))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal marginAmount = judgedPrice.subtract(costTotal).subtract(feeAmount)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal marginRate = marginAmount.divide(judgedPrice, 4, RoundingMode.HALF_UP);
        return new CostBreakdown(costSum, basis.delivery(), basis.box(), basis.commissionRate(),
                basis.effectiveCommissionRate(), basis.targetMarginRate(), costTotal, feeAmount,
                marginAmount, marginRate);
    }

    /**
     * Display (strike-through) original price that goes with a sale price the automatic calculation did not make —
     * a <b>manually set</b> one (FEATURE_2609_19 / D7) or one that followed the market (FEATURE_2610_27 / D31). The
     * register / [수정 요청] payload always carries {@code originalPrice}, so such a price that is saved without
     * refreshing it would leave a "sale price > original price" inversion on the market. The margin preset lookup
     * lives here so callers don't duplicate it.
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
                                   BigDecimal minMarginAmount, BigDecimal minMarginRate,
                                   CustomerShippingFee customerShippingFee) {

        /** Basis without a delivery-fee commission (every cell but 11st and ESM; tests). */
        public CellPricingBasis(BigDecimal commissionRate, BigDecimal effectiveCommissionRate,
                                BigDecimal targetMarginRate, BigDecimal displayDiscountRate,
                                BigDecimal minMarginAmount, BigDecimal minMarginRate) {
            this(commissionRate, effectiveCommissionRate, targetMarginRate, displayDiscountRate,
                    minMarginAmount, minMarginRate, CustomerShippingFee.NONE);
        }
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

        public CustomerShippingFee customerShippingFee() {
            return cellBasis.customerShippingFee() != null ? cellBasis.customerShippingFee() : CustomerShippingFee.NONE;
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
