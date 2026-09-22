package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.dto.response.ProductUsageResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.PriceChangeLogRepository;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.PurchaseRecordRepository;
import com.pms.repository.ShipmentParcelItemRepository;
import com.pms.repository.ShoppingListItemRepository;
import com.pms.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Where is this product used?" (FEATURE_2609_69 / A).
 *
 * <p>One read that answers both questions the duplicate-product cleanup needs: which side of a conflicting
 * pair is actually wired into masters and channel cells, and whether a product may be deleted at all.
 * {@code ProductServiceImpl.deleteProduct} calls the very same method for its guard — there is no
 * guard-free back door (PLAN D6-a), so the merge flow (03) must move references first and only then
 * delete.</p>
 *
 * <p>Tenant isolation flows through {@link Product}'s {@code @TenantId}: a product of another tenant is
 * simply not found → 404, never 403. Do not add manual tenant conditions here — none of the link/history
 * entities carry a tenant column, they inherit isolation from the product they point at.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductUsageService {

    private final ProductRepository productRepository;
    private final MasterProductComponentRepository masterProductComponentRepository;
    private final MasterProductOptionItemRepository masterProductOptionItemRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final StockMovementRepository stockMovementRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final ShipmentParcelItemRepository shipmentParcelItemRepository;
    private final ProductImageRepository productImageRepository;
    private final ShoppingListItemRepository shoppingListItemRepository;
    private final PriceChangeLogRepository priceChangeLogRepository;

    /**
     * Full usage of one product.
     *
     * <p>Inactive (soft-deleted) products are returned too — after a merge the operator still wants to see
     * what the discarded product was attached to.</p>
     *
     * @throws ResourceNotFoundException when no product of the current tenant has that id
     */
    public ProductUsageResponse getUsage(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        List<ProductUsageResponse.MasterProductRef> masterProducts = collectMasterProducts(product.getId());
        List<ProductUsageResponse.ListingOptionRef> listingOptions = collectListingOptions(product.getId());

        ProductUsageResponse.HistoryCounts history = new ProductUsageResponse.HistoryCounts(
                stockMovementRepository.countByProductId(product.getId()),
                purchaseRecordRepository.countByProductId(product.getId()),
                shipmentParcelItemRepository.countByProductId(product.getId()),
                productImageRepository.countByProductId(product.getId()),
                shoppingListItemRepository.countByProductId(product.getId()),
                priceChangeLogRepository.countByProductId(product.getId()));

        boolean deletable = masterProducts.isEmpty() && listingOptions.isEmpty();

        return new ProductUsageResponse(product.getId(), masterProducts, listingOptions, history,
                deletable, buildBlockers(masterProducts, listingOptions));
    }

    /**
     * Masters composed of this product, each carrying that master's option quantities for reference.
     *
     * <p>{@code master_product_option_item} rows are grouped under their master instead of being listed on
     * their own: they are the quantity vector over the master's component set, not an independent mapping,
     * so they must not create a second reason to block deletion (PLAN D2).</p>
     */
    private List<ProductUsageResponse.MasterProductRef> collectMasterProducts(Long productId) {
        Map<Long, MasterProduct> masters = new LinkedHashMap<>();
        for (MasterProductComponent component : masterProductComponentRepository.findByProductId(productId)) {
            MasterProduct master = component.getMasterProduct();
            masters.putIfAbsent(master.getId(), master);
        }

        Map<Long, List<ProductUsageResponse.OptionQty>> quantitiesByMaster = new HashMap<>();
        // Reuses the existing fetch-joined finder (option + master in one query) — no new N+1 path.
        // 🔴 Option items never add a master to the list: they are the quantity vector over the master's
        // component set, so a master is "linked" only through master_product_component. An item whose
        // master has no component row for this product breaks that invariant — log it, do not let it
        // block a deletion.
        for (MasterProductOptionItem item
                : masterProductOptionItemRepository.findWithMasterByProductIdIn(List.of(productId))) {
            MasterProduct master = item.getOption().getMasterProduct();
            if (!masters.containsKey(master.getId())) {
                log.warn("Option item references product {} under master {} that has no component row — "
                        + "ignored for the usage decision", productId, master.getId());
                continue;
            }
            quantitiesByMaster.computeIfAbsent(master.getId(), k -> new ArrayList<>())
                    .add(new ProductUsageResponse.OptionQty(
                            item.getOption().getId(), item.getOption().getName(), item.getQuantity()));
        }

        List<ProductUsageResponse.MasterProductRef> refs = new ArrayList<>();
        for (MasterProduct master : masters.values()) {
            List<ProductUsageResponse.OptionQty> quantities =
                    new ArrayList<>(quantitiesByMaster.getOrDefault(master.getId(), List.of()));
            quantities.sort(Comparator.comparing(ProductUsageResponse.OptionQty::optionId));
            refs.add(new ProductUsageResponse.MasterProductRef(master.getId(), master.getName(), quantities));
        }
        refs.sort(Comparator.comparing(ProductUsageResponse.MasterProductRef::id));
        return refs;
    }

    /**
     * Channel listing options composed of this product (the legacy cell BOM, PLAN D11).
     *
     * <p>A listing points at (seller, platform), not at the account row, so the account is resolved per
     * distinct pair and cached — the same channel usually repeats across a product's options.</p>
     */
    private List<ProductUsageResponse.ListingOptionRef> collectListingOptions(Long productId) {
        Map<String, Optional<MarketplaceAccount>> accountCache = new HashMap<>();
        List<ProductUsageResponse.ListingOptionRef> refs = new ArrayList<>();

        for (ProductListingProduct line : productListingProductRepository.findByProductId(productId)) {
            ProductListingOption option = line.getProductListingOption();
            ProductListing listing = option.getProductListing();
            Platform platform = listing.getPlatform();
            Long sellerId = listing.getSeller().getId();

            Optional<MarketplaceAccount> account = accountCache.computeIfAbsent(
                    sellerId + "|" + platform.name(),
                    key -> marketplaceAccountRepository.findBySeller_IdAndPlatform(sellerId, platform));

            refs.add(new ProductUsageResponse.ListingOptionRef(
                    option.getId(),
                    option.getOptionName(),
                    account.map(MarketplaceAccount::getId).orElse(null),
                    account.map(MarketplaceAccount::getAccountAlias).orElse(null),
                    platform.name(),
                    line.getQuantity(),
                    listing.getStatus() == null ? null : listing.getStatus().name()));
        }

        refs.sort(Comparator.comparing(ProductUsageResponse.ListingOptionRef::id));
        return refs;
    }

    /**
     * Noun phrases naming what blocks deletion and where to unlink it.
     *
     * <p>🔴 Noun phrases, not sentences: {@link com.pms.exception.ProductInUseException} joins them with
     * " · " and appends the closing clause, and the web screen prints them verbatim. A full sentence here
     * would bury a period in the middle of the joined message.</p>
     */
    private List<String> buildBlockers(List<ProductUsageResponse.MasterProductRef> masterProducts,
                                       List<ProductUsageResponse.ListingOptionRef> listingOptions) {
        List<String> blockers = new ArrayList<>();
        if (!masterProducts.isEmpty()) {
            blockers.add("마스터 상품 " + masterProducts.size() + "개(마스터 상품 화면에서 해제)");
        }
        if (!listingOptions.isEmpty()) {
            blockers.add("판매 옵션 " + listingOptions.size() + "개(셀 화면에서 해제)");
        }
        return blockers;
    }
}
