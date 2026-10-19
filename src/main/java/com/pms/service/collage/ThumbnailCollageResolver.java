package com.pms.service.collage;

import com.pms.domain.CollageLayout;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ThumbnailCollage;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.ThumbnailCollageRepository;
import com.pms.service.MasterProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which collage a listing's thumbnail uses (FEATURE_2610_21 D3 · D8 · D37 · D42 · D43).
 *
 * <p>Stage order = listing ({@code LISTING:{id}}) ?? its seller ({@code SELLER:{sellerId}}) ?? master default
 * ({@code MASTER}); the first stored row wins. That row switched off (D42), no row at all, or a master with fewer
 * than 2 components ({@link MasterProductService#isBundle}, D3) → empty = the single-photo thumbnail. The returned
 * layout is already adjusted to the current components ({@link CollageLayouts#adjust}).</p>
 *
 * <p>Usage: {@code thumbnailCollageResolver.resolve(cell).ifPresent(c -> render(c.layout(), c.products()));}</p>
 *
 * <p>⚠️ {@code masterProductService} is a {@code @Lazy} field: MasterProductService → MasterPropagationService →
 * ListingAssetService → this bean would otherwise be a constructor cycle (same pattern as
 * {@code ListingAssetServiceImpl.listingChannelResolver}). File: {@code service/collage/ThumbnailCollageResolver.java}.</p>
 */
@Component
@RequiredArgsConstructor
public class ThumbnailCollageResolver {

    private final ThumbnailCollageRepository thumbnailCollageRepository;
    private final MasterProductComponentRepository masterProductComponentRepository;

    @Autowired
    @Lazy
    private MasterProductService masterProductService;

    /** A resolved collage: the adjusted layout and the components (ascending id) it draws. */
    public record ResolvedCollage(CollageLayout layout, List<Product> products) {
    }

    /** The collage this listing's thumbnail uses, or empty for the single-photo thumbnail. */
    public Optional<ResolvedCollage> resolve(ProductListing cell) {
        MasterProduct master = cell.getMasterProduct();
        if (master == null) {
            return Optional.empty();
        }
        ThumbnailCollage row = pick(rowsByScopeKey(master.getId()), cell);
        if (row == null || !Boolean.TRUE.equals(row.getEnabled()) || row.getLayout() == null) {
            return Optional.empty();
        }
        if (!masterProductService.isBundle(master.getId())) {
            return Optional.empty();
        }
        List<Product> products = components(master.getId());
        List<Long> ids = products.stream().map(Product::getId).toList();
        return Optional.of(new ResolvedCollage(CollageLayouts.adjust(row.getLayout(), ids), products));
    }

    /** Every stored stage row of a master keyed by {@code scope_key}. */
    public Map<String, ThumbnailCollage> rowsByScopeKey(Long masterId) {
        Map<String, ThumbnailCollage> rows = new HashMap<>();
        for (ThumbnailCollage row : thumbnailCollageRepository.findByMasterProduct_Id(masterId)) {
            rows.put(row.getScopeKey(), row);
        }
        return rows;
    }

    /** The first stored row of listing ?? seller ?? master for this listing, or null (D37). */
    public static ThumbnailCollage pick(Map<String, ThumbnailCollage> rows, ProductListing cell) {
        ThumbnailCollage own = rows.get(CollageScopes.listing(cell.getId()));
        if (own != null) {
            return own;
        }
        if (cell.getSeller() != null) {
            ThumbnailCollage seller = rows.get(CollageScopes.seller(cell.getSeller().getId()));
            if (seller != null) {
                return seller;
            }
        }
        return rows.get(CollageScopes.MASTER);
    }

    /** The master's component products, ascending id (D12). */
    public List<Product> components(Long masterId) {
        return masterProductComponentRepository.findWithProductByMasterProductIdIn(List.of(masterId)).stream()
                .map(MasterProductComponent::getProduct)
                .sorted(Comparator.comparing(Product::getId))
                .toList();
    }
}
