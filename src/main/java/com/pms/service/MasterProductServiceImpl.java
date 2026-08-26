package com.pms.service;

import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterImageZoneAssignment;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.Package;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.Seller;
import com.pms.dto.request.MasterCategoryRequest;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.request.MasterProductUpdateRequest;
import com.pms.dto.response.ListingMatrixResponse;
import com.pms.dto.response.ListingMatrixResponse.MatrixCell;
import com.pms.dto.response.ListingMatrixResponse.MatrixRow;
import com.pms.dto.response.MasterCategoryResponse;
import com.pms.dto.response.MasterOptionResponse;
import com.pms.dto.response.MasterProductResponse;
import com.pms.exception.MasterProductInUseException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PackageRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.listing.MasterPropagationService;
import com.pms.service.listing.TagMergeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Master product definition (CRUD + options) + the channel coverage matrix (FEATURE_2608_06 / 3a, 3b-1).
 *
 * <p>The master owns a <b>component set</b> ({@link MasterProductComponent}, membership only) and any
 * number of <b>options</b> ({@link MasterProductOption}) whose quantity vectors
 * ({@link MasterProductOptionItem}) must cover the full component set with each quantity ≥ 1. Those
 * child entities have no {@code @TenantId}; isolation flows through the master, which is tenant-scoped
 * via {@code findScopedById} (a cross-tenant/absent id yields 404).</p>
 *
 * <p>⚠️ Write methods are {@code @Transactional} so a component change that breaks an existing option
 * rolls the whole PATCH back (delete + re-insert + option re-validation are one unit).</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MasterProductServiceImpl implements MasterProductService {

    private final MasterProductRepository masterProductRepository;
    private final MasterProductComponentRepository componentRepository;
    private final MasterProductOptionRepository optionRepository;
    private final MasterProductOptionItemRepository optionItemRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryMappingRepository categoryMappingRepository;
    private final CarrierRateRepository carrierRateRepository;
    private final PackageRepository packageRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final MasterImageZoneAssignmentRepository masterImageZoneAssignmentRepository;
    private final SellerRepository sellerRepository;
    private final ImageStorageService imageStorageService;
    private final ImageValidator imageValidator;
    private final MasterPropagationService masterPropagationService;
    private final TagMergeService tagMergeService;
    private final RegistrationNameGenerator registrationNameGenerator;

    private static final String IMAGE_STORAGE_CATEGORY = "master";

    // ---------------------------------------------------------------- reads

    @Override
    public List<MasterProductResponse> getMasterProducts() {
        // Active only — soft-deleted (active=false) masters are hidden from the list (recover via PATCH active=true).
        List<MasterProduct> masters = masterProductRepository.findByActiveTrue();
        List<Long> ids = masters.stream().map(MasterProduct::getId).toList();
        // Resolve the list cover from the __source__ mapping (37) in one batch query. Priority mirrors
        // resolveBaseImage: mapped cover > legacy master.sourceImageUrl (kept when no mapping exists).
        Map<Long, String> coverByMaster = ids.isEmpty()
                ? Map.of()
                : masterImageZoneAssignmentRepository
                        .findZoneImageUrlsByMasterIds(MasterImageZoneAssignment.SOURCE_ZONE, ids).stream()
                        .collect(Collectors.toMap(
                                r -> (Long) r[0], r -> (String) r[1], (first, dup) -> first));
        return masters.stream()
                .map(master -> {
                    MasterProductResponse response = mapToResponse(master);
                    String cover = coverByMaster.get(master.getId());
                    return cover != null ? response.toBuilder().sourceImageUrl(cover).build() : response;
                })
                .toList();
    }

    @Override
    public MasterProductResponse getMasterProduct(Long id) {
        // Single-fetch path only computes the registration name (2~3 extra queries per master). The list
        // path (mapToResponse) leaves it null to avoid an N×3 query amplification (32).
        MasterProduct master = requireScopedMaster(id);
        return mapToResponse(master).toBuilder()
                .registrationName(registrationNameGenerator.generate(master))
                .build();
    }

    @Override
    public boolean isBundle(Long masterId) {
        // 63: mixed-composition (AB) = 2+ components. master null (backfill transition) → SINGLE. Single entry
        // point shared by the Coupang adapter (attributes skip + register validation) so the two can't diverge.
        if (masterId == null) {
            return false;
        }
        return componentRepository.findByMasterProductId(masterId).size() >= 2;
    }

    @Override
    public ListingMatrixResponse getMatrix(Long id) {
        MasterProduct master = requireScopedMaster(id);

        // Right side: listings under this master (1 query), then their options batched (1 query).
        List<ProductListing> listings = productListingRepository.findByMasterProductId(id);
        List<Long> listingIds = listings.stream().map(ProductListing::getId).toList();
        Map<Long, BigDecimal> priceByListing = listingIds.isEmpty()
                ? Map.of()
                : productListingOptionRepository.findByProductListingIdIn(listingIds).stream()
                        .collect(Collectors.toMap(
                                o -> o.getProductListing().getId(),
                                o -> o.getSellingPrice(),
                                (first, dup) -> first));   // single SKU expected; keep first on dupes

        // Index listings by (sellerId|platform); first wins.
        Map<String, ProductListing> listingByKey = new LinkedHashMap<>();
        for (ProductListing pl : listings) {
            listingByKey.putIfAbsent(matchKey(pl.getSeller().getId(), pl.getPlatform()), pl);
        }

        // Left side: all accounts of the tenant + batched seller names (1 query).
        List<MarketplaceAccount> accounts = marketplaceAccountRepository.findAll();
        List<Long> sellerIds = accounts.stream()
                .map(a -> a.getSeller().getId())
                .distinct()
                .toList();
        Map<Long, String> sellerNames = sellerRepository.findAllById(sellerIds).stream()
                .collect(Collectors.toMap(Seller::getId, Seller::getSellerName));

        List<MatrixRow> rows = accounts.stream().map(acc -> {
            Long sellerId = acc.getSeller().getId();
            ProductListing pl = listingByKey.get(matchKey(sellerId, acc.getPlatform()));
            MatrixCell cell = pl == null ? null : MatrixCell.builder()
                    .productListingId(pl.getId())
                    .name(pl.getName())
                    .platformProductId(pl.getPlatformProductId())
                    .sellingPrice(priceByListing.get(pl.getId()))
                    .build();
            return MatrixRow.builder()
                    .sellerId(sellerId)
                    .sellerName(sellerNames.get(sellerId))
                    .platform(acc.getPlatform())
                    .accountId(acc.getId())
                    .accountLabel(acc.getAccountAlias())
                    .registered(pl != null)
                    .cell(cell)
                    .build();
        }).toList();

        return ListingMatrixResponse.builder()
                .masterId(master.getId())
                .masterName(master.getName())
                .rows(rows)
                .build();
    }

    // ---------------------------------------------------------------- master CRUD

    @Override
    @Transactional
    public MasterProductResponse createMasterProduct(MasterProductRequest request) {
        List<Product> products = requireProducts(request.getComponentProductIds());
        Set<Long> componentIds = products.stream().map(Product::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Atomicity: pre-validate every option (coverage + quantity) BEFORE any save. A violation throws
        // here, so the master is never persisted — provable by mock (masterProductRepository.save is never
        // reached), not just by @Transactional rollback. null/empty options = master-only (backward compat).
        List<MasterOptionRequest> options = request.getOptions();
        if (options != null) {
            for (MasterOptionRequest option : options) {
                assertCoversComponents(componentIds, toVector(option), "옵션은 구성상품 전체를 포함해야 합니다");
            }
        }

        MasterProduct saved = masterProductRepository.save(MasterProduct.builder()
                .name(request.getName())
                .fieldValues(request.getFieldValues())
                .active(true)
                .defaultDelivery(request.getDefaultDeliveryId() != null
                        ? requireDelivery(request.getDefaultDeliveryId()) : null)
                .defaultPackage(request.getDefaultPackageId() != null
                        ? requirePackage(request.getDefaultPackageId()) : null)
                .build());

        for (Product product : products) {
            componentRepository.save(MasterProductComponent.builder()
                    .masterProduct(saved).product(product).build());
        }

        if (options != null) {
            for (MasterOptionRequest option : options) {
                persistOption(saved, option, componentIds);
            }
        }
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public MasterProductResponse updateMasterProduct(Long id, MasterProductUpdateRequest request) {
        MasterProduct existing = requireScopedMaster(id);

        MasterProduct updated = masterProductRepository.save(existing.toBuilder()
                .name(request.getName() != null ? request.getName() : existing.getName())
                .fieldValues(request.getFieldValues() != null ? request.getFieldValues() : existing.getFieldValues())
                .active(request.getActive() != null ? request.getActive() : existing.getActive())
                // null = keep existing; a given id replaces (explicit unset via null is a follow-up).
                .defaultDelivery(request.getDefaultDeliveryId() != null
                        ? requireDelivery(request.getDefaultDeliveryId()) : existing.getDefaultDelivery())
                .defaultPackage(request.getDefaultPackageId() != null
                        ? requirePackage(request.getDefaultPackageId()) : existing.getDefaultPackage())
                .build());

        if (request.getComponentProductIds() != null) {
            List<Product> products = requireProducts(request.getComponentProductIds());

            // Replace the component set (delete + re-insert), then re-validate every existing option
            // against the new set. A violation throws → the whole @Transactional PATCH rolls back.
            // ⚠️ flush() after the delete: Hibernate's action queue runs INSERTs before entity DELETEs in one
            // flush, so re-inserting an unchanged (master, product) pair would collide with the not-yet-deleted
            // row on UQ_MPC (unique index). Forcing the delete first makes the re-insert safe.
            componentRepository.deleteByMasterProductId(id);
            componentRepository.flush();
            for (Product product : products) {
                componentRepository.save(MasterProductComponent.builder()
                        .masterProduct(updated).product(product).build());
            }

            Set<Long> newComponentIds = products.stream().map(Product::getId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<MasterProductOption> options = optionRepository.findByMasterProductId(id);
            List<Long> optionIds = options.stream().map(MasterProductOption::getId).toList();
            List<MasterProductOptionItem> items = optionIds.isEmpty()
                    ? List.of() : optionItemRepository.findByOptionIdIn(optionIds);
            Map<Long, List<MasterProductOptionItem>> itemsByOption = items.stream()
                    .collect(Collectors.groupingBy(it -> it.getOption().getId()));
            for (MasterProductOption option : options) {
                Map<Long, Integer> vector = itemsByOption.getOrDefault(option.getId(), List.of()).stream()
                        .collect(Collectors.toMap(it -> it.getProduct().getId(), MasterProductOptionItem::getQuantity));
                assertCoversComponents(newComponentIds, vector, "구성 변경이 기존 옵션과 불일치");
            }
        }

        // Layer-A auto-trigger (3d): re-generate linked cells after the master content is saved. Each cell runs
        // in its own REQUIRES_NEW transaction (this @Transactional update stays open), so a cell failure never
        // rolls the master save back. Cells without generated assets are a no-op (skip) — safe for tests too.
        masterPropagationService.propagate(id);

        return mapToResponse(updated);
    }

    @Override
    @Transactional
    public MasterProductResponse updateTags(Long id, List<String> tags) {
        MasterProduct master = requireScopedMaster(id);
        // Order-preserving dedup; an empty list clears the pool. UI reads the current value.
        MasterProduct updated = masterProductRepository.save(
                master.toBuilder().tags(tagMergeService.dedup(tags)).build());
        return mapToResponse(updated);
    }

    @Override
    @Transactional
    public void deleteMasterProduct(Long id) {
        MasterProduct existing = requireScopedMaster(id);
        // Block delete while any channel cell is live on the market (platformProductId != null) — deleting
        // the master would orphan the market listings. The user must stop those channels first (409).
        long onMarket = productListingRepository.findByMasterProductId(id).stream()
                .filter(l -> l.getPlatformProductId() != null)
                .count();
        if (onMarket > 0) {
            throw new MasterProductInUseException(onMarket);
        }
        masterProductRepository.save(existing.toBuilder().active(false).build());
    }

    // ---------------------------------------------------------------- option CRUD

    @Override
    @Transactional
    public MasterOptionResponse createOption(Long masterId, MasterOptionRequest request) {
        MasterProduct master = requireScopedMaster(masterId);
        MasterProductOption option = persistOption(master, request, componentProductIds(masterId));
        return mapToOptionResponse(option, toVector(request));
    }

    @Override
    @Transactional
    public MasterOptionResponse updateOption(Long masterId, Long optionId, MasterOptionRequest request) {
        requireScopedMaster(masterId);
        MasterProductOption option = requireOption(masterId, optionId);

        Map<Long, Integer> vector;
        if (request.getItems() != null) {
            vector = toVector(request);
            assertCoversComponents(componentProductIds(masterId), vector, "옵션은 구성상품 전체를 포함해야 합니다");
            optionItemRepository.deleteByOptionId(optionId);
            saveItems(option, vector);
        } else {
            vector = optionItemRepository.findByOptionId(optionId).stream()
                    .collect(Collectors.toMap(it -> it.getProduct().getId(), MasterProductOptionItem::getQuantity));
        }

        // Override fields follow the items rule: a given id/map replaces, null keeps the existing override.
        // For the category-meta maps, null = keep existing; a value (empty map included) = replace (59).
        MasterProductOption updated = optionRepository.save(option.toBuilder()
                .name(request.getName())
                .delivery(request.getDeliveryId() != null ? requireDelivery(request.getDeliveryId()) : option.getDelivery())
                .package_(request.getPackageId() != null ? requirePackage(request.getPackageId()) : option.getPackage_())
                .categoryAttributes(request.getCategoryAttributes() != null
                        ? request.getCategoryAttributes() : option.getCategoryAttributes())
                .categoryNotices(request.getCategoryNotices() != null
                        ? request.getCategoryNotices() : option.getCategoryNotices())
                .build());
        return mapToOptionResponse(updated, vector);
    }

    @Override
    @Transactional
    public void deleteOption(Long masterId, Long optionId) {
        requireScopedMaster(masterId);
        MasterProductOption option = requireOption(masterId, optionId);
        optionItemRepository.deleteByOptionId(optionId);
        optionRepository.delete(option);
    }

    // ---------------------------------------------------------------- standard category (single, 44)

    @Override
    @Transactional
    public MasterCategoryResponse setCategory(Long masterId, MasterCategoryRequest request) {
        MasterProduct master = requireScopedMaster(masterId);
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category", request.getCategoryId()));
        // A master may only pick a selectable leaf that is mapped to Coupang (FEATURE_2608_06 / 52).
        if (categoryRepository.existsByParentId(request.getCategoryId())) {
            throw new IllegalArgumentException("세부(leaf) 카테고리만 지정할 수 있습니다.");
        }
        if (!categoryMappingRepository.existsByCategoryIdAndPlatform(request.getCategoryId(), "COUPANG")) {
            throw new IllegalArgumentException("쿠팡 카테고리 매핑이 없습니다.");
        }
        masterProductRepository.save(master.toBuilder().category(category).build());
        return toCategoryResponse(category);
    }

    @Override
    public MasterCategoryResponse getCategory(Long masterId) {
        MasterProduct master = requireScopedMaster(masterId);
        return toCategoryResponse(master.getCategory());
    }

    @Override
    @Transactional
    public void clearCategory(Long masterId) {
        MasterProduct master = requireScopedMaster(masterId);
        masterProductRepository.save(master.toBuilder().category(null).build());
    }

    private MasterCategoryResponse toCategoryResponse(Category category) {
        return category == null
                ? MasterCategoryResponse.builder().build()
                : MasterCategoryResponse.builder()
                        .categoryId(category.getId())
                        .categoryName(category.getName())
                        .build();
    }

    // ---------------------------------------------------------------- image override (3b-2)

    @Override
    @Transactional
    public MasterProductResponse uploadMasterImage(Long id, MultipartFile file) {
        MasterProduct master = requireScopedMaster(id);
        imageValidator.validate(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("업로드 파일을 읽을 수 없습니다", e);
        }
        String url = imageStorageService.uploadBytes(
                bytes, IMAGE_STORAGE_CATEGORY,
                "master_" + id + "_" + System.currentTimeMillis() + ".jpg", file.getContentType());
        MasterProduct updated = masterProductRepository.save(
                master.toBuilder().sourceImageUrl(url).build());
        return mapToResponse(updated);
    }

    // ---------------------------------------------------------------- helpers

    /** Tenant-scoped fetch; a cross-tenant/absent id yields 404 (findScopedById is @TenantId-filtered). */
    private MasterProduct requireScopedMaster(Long id) {
        return masterProductRepository.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", id));
    }

    /** Option must belong to the given master (else 404). */
    private MasterProductOption requireOption(Long masterId, Long optionId) {
        return optionRepository.findById(optionId)
                .filter(o -> o.getMasterProduct().getId().equals(masterId))
                .orElseThrow(() -> new ResourceNotFoundException("MasterProductOption", optionId));
    }

    /** Fetch all requested products (deduped); a missing id yields 404. */
    private List<Product> requireProducts(List<Long> productIds) {
        Set<Long> distinctIds = new LinkedHashSet<>(productIds);
        List<Product> products = productRepository.findAllById(distinctIds);
        Set<Long> foundIds = products.stream().map(Product::getId).collect(Collectors.toSet());
        for (Long pid : distinctIds) {
            if (!foundIds.contains(pid)) {
                throw new ResourceNotFoundException("Product", pid);
            }
        }
        return products;
    }

    /** Fetch a delivery (CarrierRate) by id (404 if absent). */
    private CarrierRate requireDelivery(Long id) {
        return carrierRateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CarrierRate", id));
    }

    /** Fetch a box (Package) by id (404 if absent). */
    private Package requirePackage(Long id) {
        return packageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Package", id));
    }

    /** The master's component product ids. */
    private Set<Long> componentProductIds(Long masterId) {
        return componentRepository.findByMasterProductId(masterId).stream()
                .map(c -> c.getProduct().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * MUST-KEEP: an option's product vector must equal the master's component set (full coverage) AND
     * every quantity must be ≥ 1. A subset/superset throws {@code subsetMessage}; a bad quantity throws
     * "수량은 1 이상". Both map to 400.
     */
    private void assertCoversComponents(Set<Long> componentIds, Map<Long, Integer> vector, String subsetMessage) {
        if (!vector.keySet().equals(componentIds)) {
            throw new IllegalArgumentException(subsetMessage);
        }
        for (Integer quantity : vector.values()) {
            if (quantity == null || quantity < 1) {
                throw new IllegalArgumentException("수량은 1 이상");
            }
        }
    }

    /** Request items → (productId → quantity), null items → empty (fails coverage → 400). */
    private Map<Long, Integer> toVector(MasterOptionRequest request) {
        if (request.getItems() == null) {
            return Map.of();
        }
        return request.getItems().stream()
                .collect(Collectors.toMap(
                        MasterOptionRequest.OptionItem::getProductId,
                        MasterOptionRequest.OptionItem::getQuantity,
                        (first, dup) -> dup, LinkedHashMap::new));
    }

    /**
     * Validate one option against the master's component set (full coverage, quantity ≥ 1) and persist the
     * option row + its item vector. Single source of option persistence — shared by {@link #createOption}
     * (single add) and {@link #createMasterProduct} (atomic batch). Coverage is re-asserted here so a direct
     * add is safe; the atomic create additionally pre-validates all options before any save.
     */
    private MasterProductOption persistOption(MasterProduct master, MasterOptionRequest request, Set<Long> componentIds) {
        Map<Long, Integer> vector = toVector(request);
        assertCoversComponents(componentIds, vector, "옵션은 구성상품 전체를 포함해야 합니다");
        MasterProductOption option = optionRepository.save(MasterProductOption.builder()
                .masterProduct(master).name(request.getName())
                .delivery(request.getDeliveryId() != null ? requireDelivery(request.getDeliveryId()) : null)
                .package_(request.getPackageId() != null ? requirePackage(request.getPackageId()) : null)
                .categoryAttributes(request.getCategoryAttributes())    // null = no override (59)
                .categoryNotices(request.getCategoryNotices())
                .build());
        saveItems(option, vector);
        return option;
    }

    private void saveItems(MasterProductOption option, Map<Long, Integer> vector) {
        Map<Long, Product> products = productRepository.findAllById(vector.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        vector.forEach((productId, quantity) -> optionItemRepository.save(MasterProductOptionItem.builder()
                .option(option).product(products.get(productId)).quantity(quantity).build()));
    }

    private MasterProductResponse mapToResponse(MasterProduct master) {
        Long id = master.getId();
        List<MasterProductComponent> components = componentRepository.findByMasterProductId(id);
        List<MasterProductOption> options = optionRepository.findByMasterProductId(id);
        List<Long> optionIds = options.stream().map(MasterProductOption::getId).toList();
        List<MasterProductOptionItem> items = optionIds.isEmpty()
                ? List.of() : optionItemRepository.findByOptionIdIn(optionIds);

        // Batch every referenced product name in one query (N+1 guard).
        Set<Long> productIds = new LinkedHashSet<>();
        components.forEach(c -> productIds.add(c.getProduct().getId()));
        items.forEach(it -> productIds.add(it.getProduct().getId()));
        Map<Long, String> names = productIds.isEmpty()
                ? Map.of()
                : productRepository.findAllById(productIds).stream()
                        .collect(Collectors.toMap(Product::getId, Product::getProductName));

        List<MasterProductResponse.Component> componentResponses = components.stream()
                .map(c -> MasterProductResponse.Component.builder()
                        .productId(c.getProduct().getId())
                        .productName(names.get(c.getProduct().getId()))
                        .build())
                .toList();

        Map<Long, List<MasterProductOptionItem>> itemsByOption = items.stream()
                .collect(Collectors.groupingBy(it -> it.getOption().getId()));
        List<MasterOptionResponse> optionResponses = options.stream()
                .map(o -> MasterOptionResponse.builder()
                        .id(o.getId())
                        .name(o.getName())
                        .deliveryId(o.getDelivery() != null ? o.getDelivery().getId() : null)
                        .packageId(o.getPackage_() != null ? o.getPackage_().getId() : null)
                        .categoryAttributes(o.getCategoryAttributes())
                        .categoryNotices(o.getCategoryNotices())
                        .items(itemsByOption.getOrDefault(o.getId(), List.of()).stream()
                                .map(it -> MasterOptionResponse.Item.builder()
                                        .productId(it.getProduct().getId())
                                        .productName(names.get(it.getProduct().getId()))
                                        .quantity(it.getQuantity())
                                        .build())
                                .toList())
                        .build())
                .toList();

        return MasterProductResponse.builder()
                .id(master.getId())
                .name(master.getName())
                .active(master.getActive())
                .sourceImageUrl(master.getSourceImageUrl())
                .fieldValues(master.getFieldValues())
                .tags(master.getTags())
                .defaultDeliveryId(master.getDefaultDelivery() != null ? master.getDefaultDelivery().getId() : null)
                .defaultPackageId(master.getDefaultPackage() != null ? master.getDefaultPackage().getId() : null)
                .components(componentResponses)
                .options(optionResponses)
                .build();
    }

    private MasterOptionResponse mapToOptionResponse(MasterProductOption option, Map<Long, Integer> vector) {
        Map<Long, String> names = vector.isEmpty()
                ? Map.of()
                : productRepository.findAllById(vector.keySet()).stream()
                        .collect(Collectors.toMap(Product::getId, Product::getProductName));
        List<MasterOptionResponse.Item> items = vector.entrySet().stream()
                .map(e -> MasterOptionResponse.Item.builder()
                        .productId(e.getKey())
                        .productName(names.get(e.getKey()))
                        .quantity(e.getValue())
                        .build())
                .toList();
        return MasterOptionResponse.builder()
                .id(option.getId())
                .name(option.getName())
                .deliveryId(option.getDelivery() != null ? option.getDelivery().getId() : null)
                .packageId(option.getPackage_() != null ? option.getPackage_().getId() : null)
                .categoryAttributes(option.getCategoryAttributes())
                .categoryNotices(option.getCategoryNotices())
                .items(items)
                .build();
    }

    private static String matchKey(Long sellerId, String platform) {
        return sellerId + "|" + platform;
    }
}
