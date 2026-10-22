package com.pms.service.price;

import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.ListingMarginResponse;
import com.pms.dto.response.ListingMarginResponse.CostComponent;
import com.pms.dto.response.ListingMarginResponse.OptionMargin;
import com.pms.dto.response.ListingMarginResponse.SiteMargin;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.PriceCalculator;
import com.pms.service.listing.CellBomResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link ListingMarginService} (FEATURE_2610_27). The formula is {@link PriceCalculator#breakdown} /
 * {@link PriceCalculator#breakEvenPrice} only (D8), and 「미달」 is {@link RepricingServiceImpl#below} — the same
 * judgement as the 「대응 필요」 of the margin screen (D5).
 *
 * <p>Unlike the margin screen this covers every option of the listing — any platform, any status (D3) — and never
 * reads the market (D13).</p>
 *
 * <p>FEATURE_2610_28 / D20 · D21: every option carries one entry per site — the platform name on a Coupang or 11st
 * listing, {@code GMARKET} then {@code AUCTION} on an ESM listing — so a site that is managed apart later needs no new
 * shape. D7 · D12 · D13: each entry also carries the components, the fee split in two and the preset minimums.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingMarginServiceImpl implements ListingMarginService {

    /** D12: the reason the margin screen gives for an option without a master option (2609_71). */
    static final String UNMAPPED_REASON = "마스터에 연결되지 않은 옵션이라 구성품을 알 수 없습니다";

    /** FEATURE_2610_28 / D21: the 400 for a preview of a site the listing is not sold on. */
    static final String FOREIGN_SITE_REASON = "이 판매상품에 없는 쇼핑몰입니다: ";

    /** FEATURE_2610_28 / D21: the two ESM site names, spelled as the ESM site enum spells them. */
    private static final String GMARKET = "GMARKET";
    private static final String AUCTION = "AUCTION";

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final CellBomResolver cellBomResolver;
    private final PriceCalculator priceCalculator;

    @Override
    public ListingMarginResponse margins(Long listingId) {
        ProductListing cell = scoped(listingId);
        boolean esm = cell.getPlatform() == Platform.ESM;
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(listingId).stream()
                .sorted(Comparator.comparing(ProductListingOption::getId))
                .toList();
        Map<Long, CellBomResolver.Bom> boms = cellBomResolver.forOptions(options);
        Basis basis = basis(() -> priceCalculator.resolveCellBasis(cell));
        Basis auctionBasis = esm ? basis(() -> priceCalculator.resolveAuctionCellBasis(cell)) : null;

        List<OptionMargin> rows = options.stream()
                .map(option -> {
                    CellBomResolver.Bom bom = boms.getOrDefault(option.getId(), CellBomResolver.Bom.UNMAPPED);
                    List<SiteMargin> sites = esm
                            ? List.of(
                                    site(GMARKET, cell, option, bom, basis,
                                            option.getMarketPrice(), option.getSellingPrice()),
                                    site(AUCTION, cell, option, bom, auctionBasis,
                                            option.getAuctionMarketPrice(), option.getAuctionSellingPrice()))
                            : List.of(site(cell.getPlatform().name(), cell, option, bom, basis,
                                    option.getMarketPrice(), option.getSellingPrice()));
                    return new OptionMargin(option.getId(), option.getOptionName(), sites);
                })
                .toList();
        return new ListingMarginResponse(cell.getId(), cell.getPlatform().name(), rows);
    }

    @Override
    public SiteMargin preview(Long listingId, Long optionId, BigDecimal price, String site) {
        ProductListing cell = scoped(listingId);
        if (!sitesOf(cell).contains(site)) {
            throw new IllegalArgumentException(FOREIGN_SITE_REASON + site);
        }
        ProductListingOption option = productListingOptionRepository.findByProductListingId(listingId).stream()
                .filter(o -> o.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("리스팅 옵션 아님"));
        CellBomResolver.Bom bom = cellBomResolver.forOptions(List.of(option))
                .getOrDefault(option.getId(), CellBomResolver.Bom.UNMAPPED);
        Basis basis = AUCTION.equals(site)
                ? basis(() -> priceCalculator.resolveAuctionCellBasis(cell))
                : basis(() -> priceCalculator.resolveCellBasis(cell));
        return judge(site, cell, option, bom, basis, null, null, null, price, false);
    }

    /** D21: the sites a listing is sold on — Gmarket and Auction on ESM, the platform itself elsewhere. */
    private static List<String> sitesOf(ProductListing cell) {
        return cell.getPlatform() == Platform.ESM ? List.of(GMARKET, AUCTION) : List.of(cell.getPlatform().name());
    }

    private SiteMargin site(String site, ProductListing cell, ProductListingOption option, CellBomResolver.Bom bom,
                            Basis basis, BigDecimal marketPrice, BigDecimal systemPrice) {
        BigDecimal judgedPrice = marketPrice != null ? marketPrice : systemPrice;
        return judge(site, cell, option, bom, basis, option.getMarketPriceAt(), marketPrice, systemPrice, judgedPrice,
                marketPrice == null);
    }

    /** D8 · D12: breakdown + break-even + 「미달」, or the reason it cannot be calculated. */
    private SiteMargin judge(String site, ProductListing cell, ProductListingOption option, CellBomResolver.Bom bom,
                             Basis basis, LocalDateTime marketPriceAt, BigDecimal marketPrice, BigDecimal systemPrice,
                             BigDecimal judgedPrice, boolean marketPriceMissing) {
        if (basis.error() != null) {
            return uncalculable(site, marketPriceAt, marketPrice, systemPrice, judgedPrice, marketPriceMissing,
                    basis.error());
        }
        if (bom.unmapped()) {
            return uncalculable(site, marketPriceAt, marketPrice, systemPrice, judgedPrice, marketPriceMissing,
                    UNMAPPED_REASON);
        }
        try {
            PriceCalculator.PricingBasis pricingBasis =
                    priceCalculator.resolveBasis(basis.basis(), cell, option.getMasterProductOption());
            BigDecimal costSum = RepricingServiceImpl.costSum(bom);
            PriceCalculator.CostBreakdown breakdown = priceCalculator.breakdown(pricingBasis, costSum, judgedPrice);
            // FEATURE_2610_28 / D12: the fee in two parts. The delivery-fee commission is the very term breakdown adds
            // to feeAmount; the sale commission is the rest, so the two parts always sum to feeAmount.
            BigDecimal shippingFeeCommission = pricingBasis.customerShippingFee().costAt(judgedPrice)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal commissionAmount = breakdown.feeAmount().subtract(shippingFeeCommission);
            return new SiteMargin(site, marketPriceAt, marketPrice, systemPrice, judgedPrice, marketPriceMissing,
                    breakdown.costSum(), components(bom), breakdown.delivery(), breakdown.box(),
                    breakdown.effectiveCommissionRate(), commissionAmount, shippingFeeCommission,
                    breakdown.feeAmount(), breakdown.marginAmount(), breakdown.marginRate(),
                    priceCalculator.breakEvenPrice(pricingBasis, costSum),
                    basis.basis().minMarginAmount(), basis.basis().minMarginRate(),
                    RepricingServiceImpl.below(basis.basis(), breakdown), null);
        } catch (RuntimeException e) {
            return uncalculable(site, marketPriceAt, marketPrice, systemPrice, judgedPrice, marketPriceMissing,
                    e.getMessage());
        }
    }

    /**
     * FEATURE_2610_28 / D7 · D14 · D15: every component in BOM order, even a single one. A component without a unit
     * price keeps a null amount — {@code costSum} leaves it out, unchanged.
     */
    private static List<CostComponent> components(CellBomResolver.Bom bom) {
        return bom.lines().stream()
                .map(line -> {
                    BigDecimal unitPrice = line.product().getPrice();
                    BigDecimal amount = unitPrice == null
                            ? null
                            : unitPrice.multiply(BigDecimal.valueOf(line.quantity()));
                    return new CostComponent(line.productName(), line.quantity(), unitPrice, amount);
                })
                .toList();
    }

    private static SiteMargin uncalculable(String site, LocalDateTime marketPriceAt, BigDecimal marketPrice,
                                           BigDecimal systemPrice, BigDecimal judgedPrice,
                                           boolean marketPriceMissing, String reason) {
        return new SiteMargin(site, marketPriceAt, marketPrice, systemPrice, judgedPrice, marketPriceMissing,
                null, List.of(), null, null, null, null, null, null, null, null, null, null, null, false, reason);
    }

    private ProductListing scoped(Long listingId) {
        return productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
    }

    /** The cell-scoped basis once per listing, or the reason it cannot be resolved (D12). */
    private static Basis basis(Supplier<PriceCalculator.CellPricingBasis> resolve) {
        try {
            return new Basis(resolve.get(), null);
        } catch (RuntimeException e) {
            return new Basis(null, e.getMessage());
        }
    }

    private record Basis(PriceCalculator.CellPricingBasis basis, String error) {
    }
}
