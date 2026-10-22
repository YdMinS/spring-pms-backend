package com.pms.service.price;

import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.ListingMarginResponse;
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
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingMarginServiceImpl implements ListingMarginService {

    /** D12: the reason the margin screen gives for an option without a master option (2609_71). */
    static final String UNMAPPED_REASON = "마스터에 연결되지 않은 옵션이라 구성품을 알 수 없습니다";

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
                    SiteMargin margin = site(cell, option, bom, basis,
                            option.getMarketPrice(), option.getSellingPrice());
                    SiteMargin auctionMargin = esm ? site(cell, option, bom, auctionBasis,
                            option.getAuctionMarketPrice(), option.getAuctionSellingPrice()) : null;
                    return new OptionMargin(option.getId(), option.getOptionName(), option.getMarketPriceAt(),
                            margin, auctionMargin);
                })
                .toList();
        return new ListingMarginResponse(cell.getId(), cell.getPlatform().name(), rows);
    }

    @Override
    public SiteMargin preview(Long listingId, Long optionId, BigDecimal price, boolean auction) {
        ProductListing cell = scoped(listingId);
        if (auction && cell.getPlatform() != Platform.ESM) {
            throw new IllegalArgumentException("옥션 가격은 G마켓 · 옥션 판매상품에만 있습니다");
        }
        ProductListingOption option = productListingOptionRepository.findByProductListingId(listingId).stream()
                .filter(o -> o.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("리스팅 옵션 아님"));
        CellBomResolver.Bom bom = cellBomResolver.forOptions(List.of(option))
                .getOrDefault(option.getId(), CellBomResolver.Bom.UNMAPPED);
        Basis basis = auction
                ? basis(() -> priceCalculator.resolveAuctionCellBasis(cell))
                : basis(() -> priceCalculator.resolveCellBasis(cell));
        return judge(cell, option, bom, basis, null, null, price, false);
    }

    private SiteMargin site(ProductListing cell, ProductListingOption option, CellBomResolver.Bom bom, Basis basis,
                            BigDecimal marketPrice, BigDecimal systemPrice) {
        BigDecimal judgedPrice = marketPrice != null ? marketPrice : systemPrice;
        return judge(cell, option, bom, basis, marketPrice, systemPrice, judgedPrice, marketPrice == null);
    }

    /** D8 · D12: breakdown + break-even + 「미달」, or the reason it cannot be calculated. */
    private SiteMargin judge(ProductListing cell, ProductListingOption option, CellBomResolver.Bom bom, Basis basis,
                             BigDecimal marketPrice, BigDecimal systemPrice, BigDecimal judgedPrice,
                             boolean marketPriceMissing) {
        if (basis.error() != null) {
            return uncalculable(marketPrice, systemPrice, judgedPrice, marketPriceMissing, basis.error());
        }
        if (bom.unmapped()) {
            return uncalculable(marketPrice, systemPrice, judgedPrice, marketPriceMissing, UNMAPPED_REASON);
        }
        try {
            PriceCalculator.PricingBasis pricingBasis =
                    priceCalculator.resolveBasis(basis.basis(), cell, option.getMasterProductOption());
            BigDecimal costSum = RepricingServiceImpl.costSum(bom);
            PriceCalculator.CostBreakdown breakdown = priceCalculator.breakdown(pricingBasis, costSum, judgedPrice);
            return new SiteMargin(marketPrice, systemPrice, judgedPrice, marketPriceMissing,
                    breakdown.costSum(), breakdown.delivery(), breakdown.box(), breakdown.feeAmount(),
                    breakdown.marginAmount(), breakdown.marginRate(),
                    priceCalculator.breakEvenPrice(pricingBasis, costSum),
                    RepricingServiceImpl.below(basis.basis(), breakdown), null);
        } catch (RuntimeException e) {
            return uncalculable(marketPrice, systemPrice, judgedPrice, marketPriceMissing, e.getMessage());
        }
    }

    private static SiteMargin uncalculable(BigDecimal marketPrice, BigDecimal systemPrice, BigDecimal judgedPrice,
                                           boolean marketPriceMissing, String reason) {
        return new SiteMargin(marketPrice, systemPrice, judgedPrice, marketPriceMissing,
                null, null, null, null, null, null, null, false, reason);
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
