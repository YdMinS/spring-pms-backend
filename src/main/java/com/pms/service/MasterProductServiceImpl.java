package com.pms.service;

import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductCategory;
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
import com.pms.exception.BusinessException;
import com.pms.exception.MasterProductInUseException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductCategoryRepository;
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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
    private final MasterProductCategoryRepository masterProductCategoryRepository;
    private final MasterProductComponentRepository componentRepository;
    private final MasterProductOptionRepository optionRepository;
    private final MasterProductOptionItemRepository optionItemRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final CarrierRateRepository carrierRateRepository;
    private final PackageRepository packageRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final SellerRepository sellerRepository;
    private final ImageStorageService imageStorageService;
    private final ImageValidator imageValidator;
    private final MasterPropagationService masterPropagationService;

    private static final String IMAGE_STORAGE_CATEGORY = "master";

    // ---------------------------------------------------------------- reads

    @Override
    public List<MasterProductResponse> getMasterProducts() {
        // Active only — soft-deleted (active=false) masters are hidden from the list (recover via PATCH active=true).
        return masterProductRepository.findByActiveTrue().stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public MasterProductResponse getMasterProduct(Long id) {
        return mapToResponse(requireScopedMaster(id));
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

        MasterProduct saved = masterProductRepository.save(MasterProduct.builder()
                .name(request.getName())
                .detailSource(request.getDetailSource())
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
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public MasterProductResponse updateMasterProduct(Long id, MasterProductUpdateRequest request) {
        MasterProduct existing = requireScopedMaster(id);

        MasterProduct updated = masterProductRepository.save(existing.toBuilder()
                .name(request.getName() != null ? request.getName() : existing.getName())
                .detailSource(request.getDetailSource() != null ? request.getDetailSource() : existing.getDetailSource())
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
        Map<Long, Integer> vector = toVector(request);
        assertCoversComponents(componentProductIds(masterId), vector, "옵션은 구성상품 전체를 포함해야 합니다");

        MasterProductOption option = optionRepository.save(MasterProductOption.builder()
                .masterProduct(master).name(request.getName())
                .delivery(request.getDeliveryId() != null ? requireDelivery(request.getDeliveryId()) : null)
                .package_(request.getPackageId() != null ? requirePackage(request.getPackageId()) : null)
                .build());
        saveItems(option, vector);
        return mapToOptionResponse(option, vector);
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

        // Override fields follow the items rule: a given id replaces, null keeps the existing override.
        MasterProductOption updated = optionRepository.save(option.toBuilder()
                .name(request.getName())
                .delivery(request.getDeliveryId() != null ? requireDelivery(request.getDeliveryId()) : option.getDelivery())
                .package_(request.getPackageId() != null ? requirePackage(request.getPackageId()) : option.getPackage_())
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

    // ---------------------------------------------------------------- category (master × platform, 13)

    @Override
    @Transactional
    public MasterCategoryResponse upsertCategory(Long masterId, MasterCategoryRequest request) {
        MasterProduct master = requireScopedMaster(masterId);
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category", request.getCategoryId()));

        MasterProductCategory existing = masterProductCategoryRepository
                .findByMasterProductIdAndPlatform(masterId, request.getPlatform()).orElse(null);
        MasterProductCategory toSave = existing != null
                ? existing.toBuilder().category(category).build()
                : MasterProductCategory.builder()
                        .masterProduct(master).platform(request.getPlatform()).category(category).build();
        MasterProductCategory saved = masterProductCategoryRepository.save(toSave);
        return toCategoryResponse(saved);
    }

    @Override
    public List<MasterCategoryResponse> getCategories(Long masterId) {
        requireScopedMaster(masterId);
        return masterProductCategoryRepository.findByMasterProductId(masterId).stream()
                .map(this::toCategoryResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deleteCategory(Long masterId, String platform) {
        requireScopedMaster(masterId);
        MasterProductCategory existing = masterProductCategoryRepository
                .findByMasterProductIdAndPlatform(masterId, platform)
                .orElseThrow(() -> new BusinessException(
                        "MasterProductCategory not found for platform: " + platform, HttpStatus.NOT_FOUND));
        masterProductCategoryRepository.delete(existing);
    }

    private MasterCategoryResponse toCategoryResponse(MasterProductCategory mpc) {
        return MasterCategoryResponse.builder()
                .platform(mpc.getPlatform())
                .categoryId(mpc.getCategory().getId())
                .categoryName(mpc.getCategory().getName())
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
                .detailSource(master.getDetailSource())
                .fieldValues(master.getFieldValues())
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
                .items(items)
                .build();
    }

    private static String matchKey(Long sellerId, String platform) {
        return sellerId + "|" + platform;
    }
}
