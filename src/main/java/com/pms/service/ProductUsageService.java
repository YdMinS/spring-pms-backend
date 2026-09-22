package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.dto.response.ProductUsageResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.PriceChangeLogRepository;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductListingOptionRepository;
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
    private final ProductListingOptionRepository productListingOptionRepository;
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
     * Channel listing options composed of this product — resolved <b>through the master</b> (FEATURE_2609_71).
     *
     * <p>🔴 셀 구성품 사본({@code product_listing_product})은 사라졌다. 「이 물품을 쓰는 판매 옵션」은 이제
     * 마스터 옵션의 items 에서 출발해 {@code master_product_option_id} FK 를 타고 내려온 결과이며, 수량도
     * 마스터 옵션의 수량이다 — 셀마다 다른 수량이라는 개념 자체가 없어졌다.</p>
     *
     * <p>그래서 이 목록은 <b>독립적인 차단 사유가 아니다</b>: 여기 무언가 있으면 그 마스터도 반드시
     * {@code masterProducts} 에 있다(옵션 item 은 마스터의 구성상품 집합 위의 수량 벡터다, PLAN D2).
     * 끊는 곳은 마스터 한 군데이고, 이 목록은 그 파급 범위를 보여주는 정보다.</p>
     *
     * <p>A listing points at (seller, platform), not at the account row, so the account is resolved per
     * distinct pair and cached — the same channel usually repeats across a product's options.</p>
     */
    private List<ProductUsageResponse.ListingOptionRef> collectListingOptions(Long productId) {
        // masterOptionId → 이 물품의 수량. 같은 옵션에 같은 물품이 두 줄이면 첫 줄이 이긴다(다른 읽기 경로와 동일).
        Map<Long, Integer> quantityByMasterOption = new LinkedHashMap<>();
        // masterOptionId → 그 옵션이 속한 마스터. 🔴 이 finder 가 master 를 fetch join 하므로 여기서 같이
        // 담아 둔다 — 나중에 옵션마다 `getMasterProduct()` 를 타면 옵션 수만큼 쿼리가 더 나간다.
        Map<Long, Long> masterIdByMasterOption = new HashMap<>();
        for (MasterProductOptionItem item
                : masterProductOptionItemRepository.findWithMasterByProductIdIn(List.of(productId))) {
            quantityByMasterOption.putIfAbsent(item.getOption().getId(), item.getQuantity());
            masterIdByMasterOption.putIfAbsent(
                    item.getOption().getId(), item.getOption().getMasterProduct().getId());
        }
        if (quantityByMasterOption.isEmpty()) {
            return List.of();
        }

        Map<String, Optional<MarketplaceAccount>> accountCache = new HashMap<>();
        List<ProductUsageResponse.ListingOptionRef> refs = new ArrayList<>();

        for (ProductListingOption option
                : productListingOptionRepository.findByMasterProductOption_IdIn(quantityByMasterOption.keySet())) {
            ProductListing listing = option.getProductListing();
            Platform platform = listing.getPlatform();
            Long sellerId = listing.getSeller().getId();

            Optional<MarketplaceAccount> account = accountCache.computeIfAbsent(
                    sellerId + "|" + platform.name(),
                    key -> marketplaceAccountRepository.findBySeller_IdAndPlatform(sellerId, platform));

            refs.add(new ProductUsageResponse.ListingOptionRef(
                    option.getId(),
                    option.getOptionName(),
                    // 🔴 옵션 id 로는 어떤 화면도 열 수 없다. 옵션이 속한 셀을 같이 실어 보내야 화면이
                    // 판매 상품 상세로 바로 보낼 수 있다(2026-09-23).
                    listing.getId(),
                    listing.getName(),
                    masterIdByMasterOption.get(option.getMasterProductOption().getId()),
                    account.map(MarketplaceAccount::getId).orElse(null),
                    account.map(MarketplaceAccount::getAccountAlias).orElse(null),
                    platform.name(),
                    quantityByMasterOption.get(option.getMasterProductOption().getId()),
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
            blockers.add("판매 옵션 " + listingOptions.size() + "개(마스터 상품 화면에서 해제)");
        }
        return blockers;
    }
}
