package com.pms.service;

import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterImageZoneAssignment;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Package;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.Seller;
import com.pms.dto.request.MasterCategoryRequest;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.request.MasterProductUpdateRequest;
import com.pms.dto.request.OptionCheckSuffixRequest;
import com.pms.dto.response.ChannelSyncPreviewResponse;
import com.pms.dto.response.ListingMatrixResponse;
import com.pms.dto.response.ListingMatrixResponse.MatrixCell;
import com.pms.dto.response.ListingMatrixResponse.MatrixRow;
import com.pms.dto.response.MasterCategoryResponse;
import com.pms.dto.response.MasterOptionResponse;
import com.pms.dto.response.MasterProductResponse;
import com.pms.exception.MasterProductInUseException;
import com.pms.exception.ValidationException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CarrierRateRepository;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.CategoryRepository;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PackageRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.listing.MasterOptionChannelSync;
import com.pms.service.listing.MasterPropagationService;
import com.pms.service.listing.OptionCheckSuffix;
import com.pms.service.listing.OptionQuantitySync;
import com.pms.service.listing.TagMergeService;
import com.pms.service.listing.shipping.ShippingOverrideKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final ProductListingProductRepository productListingProductRepository;
    private final GeneratedProductDataRepository generatedProductDataRepository;
    private final MasterImageZoneAssignmentRepository masterImageZoneAssignmentRepository;
    private final SellerRepository sellerRepository;
    private final ImageStorageService imageStorageService;
    private final ImageValidator imageValidator;
    private final MasterPropagationService masterPropagationService;
    private final MasterOptionChannelSync masterOptionChannelSync;
    private final ListingAssetService listingAssetService;
    private final OptionQuantitySync optionQuantitySync;
    private final TagMergeService tagMergeService;
    private final RegistrationNameGenerator registrationNameGenerator;
    private final OptionCheckSuffixResolver optionCheckSuffixResolver;

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
        // 84: one batched lock judgement for the whole page (2 queries regardless of master count).
        Map<Long, Set<String>> lockedByMaster = marketRegisteredOptionNames(ids);
        return masters.stream()
                .map(master -> {
                    MasterProductResponse response = mapToResponse(
                            master, lockedByMaster.getOrDefault(master.getId(), Set.of()));
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
        // 69: master-level suffix = master override ?? system (no channel/seller context — see resolveForMaster).
        return mapToResponse(master).toBuilder()
                .registrationName(registrationNameGenerator.generate(
                        master, optionCheckSuffixResolver.resolveForMaster(master)))
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

        // Master options, loaded ONCE (67): the registration name is generated per cell from that cell's active
        // options, but the master option list feeding the single-option name lookup is shared (no per-cell re-query).
        List<MasterProductOption> masterOptions = optionRepository.findByMasterProductId(id);

        // Right side: listings under this master (1 query), then their options batched (1 query) — reused for both
        // the selling price and each listing's active option-name set (drives the per-channel registration name).
        List<ProductListing> listings = productListingRepository.findByMasterProductId(id);
        List<Long> listingIds = listings.stream().map(ProductListing::getId).toList();
        List<ProductListingOption> allOptions = listingIds.isEmpty()
                ? List.of()
                : productListingOptionRepository.findByProductListingIdIn(listingIds);
        Map<Long, BigDecimal> priceByListing = allOptions.stream()
                .collect(Collectors.toMap(
                        o -> o.getProductListing().getId(),
                        o -> o.getSellingPrice(),
                        (first, dup) -> first));   // single SKU expected; keep first on dupes
        Map<Long, Set<String>> activeNamesByListing = new LinkedHashMap<>();
        for (ProductListingOption o : allOptions) {
            if (Boolean.TRUE.equals(o.getActive())) {
                activeNamesByListing
                        .computeIfAbsent(o.getProductListing().getId(), k -> new LinkedHashSet<>())
                        .add(o.getOptionName());
            }
        }

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
            MatrixCell cell = null;
            if (pl != null) {
                // 67: registration name is always auto-generated per channel from this listing's active options.
                Set<String> activeNames = activeNamesByListing.getOrDefault(pl.getId(), Set.of());
                // 69: suffix = channel(this row's account) ?? master ?? seller ?? system. Pure overload reuses the
                // already-loaded account (row) + seller (from the sellerNames findAllById, same session) + master
                // — NO per-cell account re-query (resolve(cell) would be N DB calls).
                OptionCheckSuffix suffix = optionCheckSuffixResolver.resolve(acc, master, acc.getSeller());
                cell = MatrixCell.builder()
                        .productListingId(pl.getId())
                        .name(pl.getName())
                        .platformProductId(pl.getPlatformProductId())
                        .sellingPrice(priceByListing.get(pl.getId()))
                        .registrationName(registrationNameGenerator.generate(master, activeNames, masterOptions, suffix))
                        .status(pl.getStatus() != null ? pl.getStatus().name() : null)
                        .build();
            }
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

    /**
     * 89: read-only diff of every linked cell against the master — "what would [채널에 반영하기] change?".
     *
     * <p>⚠️ The judgement MUST mirror what propagation actually does, or the caller's banner never clears:</p>
     * <ul>
     *   <li>Cells with no {@code GeneratedProductData} are dropped entirely — {@code propagate} counts them
     *       {@code skipped} and never touches them, so a difference there is permanent.</li>
     *   <li>{@code missing}/{@code orphan} mirror {@code MasterOptionChannelSync.syncStructure} (1)/(2):
     *       matched by {@code optionName}; an orphan counts only while {@code active=true} (rows are never
     *       deleted, decision 42) and the cell is off-market — an on-market orphan is left alone by
     *       propagation (WARN only), so it is reported separately and never counted.</li>
     *   <li>Quantities mirror {@code OptionQuantitySync.syncLines}: matched by {@code productId}, shared
     *       products only, and <b>{@code active}-agnostic</b> (the quantity sync does not read {@code active}).</li>
     * </ul>
     *
     * <p>Query budget = one call per repository regardless of cell/option count (batched finders).</p>
     */
    @Override
    public ChannelSyncPreviewResponse previewChannelSync(Long masterId) {
        requireScopedMaster(masterId);

        // Master side: option name → (productId → quantity). Duplicate names / duplicate products: first wins
        // (same rule as syncLines' toMap merge), so the preview can never disagree with the propagation.
        List<MasterProductOption> masterOptions = optionRepository.findByMasterProductId(masterId);
        List<Long> masterOptionIds = masterOptions.stream().map(MasterProductOption::getId).toList();
        Map<Long, Map<Long, Integer>> masterItemsByOption = new LinkedHashMap<>();
        if (!masterOptionIds.isEmpty()) {
            for (MasterProductOptionItem item : optionItemRepository.findByOptionIdIn(masterOptionIds)) {
                masterItemsByOption
                        .computeIfAbsent(item.getOption().getId(), k -> new LinkedHashMap<>())
                        .putIfAbsent(item.getProduct().getId(), item.getQuantity());
            }
        }
        Map<String, Map<Long, Integer>> masterByName = new LinkedHashMap<>();
        for (MasterProductOption option : masterOptions) {
            masterByName.putIfAbsent(
                    option.getName(), masterItemsByOption.getOrDefault(option.getId(), Map.of()));
        }

        // Cell side: only cells propagation would actually process (generated assets present).
        List<ProductListing> allCells = productListingRepository.findByMasterProductId(masterId);
        List<Long> allCellIds = allCells.stream().map(ProductListing::getId).toList();
        Set<Long> generatedCellIds = allCellIds.isEmpty()
                ? Set.of()
                : generatedProductDataRepository.findByProductListingIdIn(allCellIds).stream()
                        .map(data -> data.getProductListing().getId())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        List<ProductListing> cells = allCells.stream()
                .filter(cell -> generatedCellIds.contains(cell.getId()))
                .toList();
        if (cells.isEmpty()) {
            return emptyPreview();
        }

        List<Long> cellIds = cells.stream().map(ProductListing::getId).toList();
        List<ProductListingOption> cellOptions = productListingOptionRepository.findByProductListingIdIn(cellIds);
        List<Long> cellOptionIds = cellOptions.stream().map(ProductListingOption::getId).toList();
        // optionId → (productId → quantity); duplicate product lines: first wins (syncLines' merge rule).
        Map<Long, Map<Long, Integer>> cellQuantitiesByOption = new LinkedHashMap<>();
        if (!cellOptionIds.isEmpty()) {
            for (ProductListingProduct line
                    : productListingProductRepository.findByProductListingOptionIdIn(cellOptionIds)) {
                cellQuantitiesByOption
                        .computeIfAbsent(line.getProductListingOption().getId(), k -> new LinkedHashMap<>())
                        .putIfAbsent(line.getProduct().getId(), line.getQuantity());
            }
        }
        Map<Long, Map<String, ProductListingOption>> optionsByCell = new LinkedHashMap<>();
        for (ProductListingOption option : cellOptions) {
            optionsByCell
                    .computeIfAbsent(option.getProductListing().getId(), k -> new LinkedHashMap<>())
                    .putIfAbsent(option.getOptionName(), option);   // same-named cell options: first wins
        }

        // Seller names in ONE query (seller is LAZY + open-in-view=false: cell.getSeller().getSellerName()
        // would be a per-cell query, and a LazyInitializationException outside the transaction).
        List<Long> sellerIds = cells.stream().map(cell -> cell.getSeller().getId()).distinct().toList();
        Map<Long, String> sellerNames = sellerRepository.findAllById(sellerIds).stream()
                .collect(Collectors.toMap(Seller::getId, Seller::getSellerName, (first, dup) -> first));

        List<ChannelSyncPreviewResponse.Channel> channels = new ArrayList<>();
        int affectedChannels = 0, missingTotal = 0, orphanTotal = 0, quantityTotal = 0;
        for (ProductListing cell : cells) {
            Map<String, ProductListingOption> cellByName =
                    optionsByCell.getOrDefault(cell.getId(), Map.of());
            boolean onMarket = cell.getPlatformProductId() != null;

            // (1) missing: master option with no row on this cell at all (active is irrelevant — the row's
            //     absence is what syncStructure fixes by creating it switched off).
            List<String> missing = masterByName.keySet().stream()
                    .filter(name -> !cellByName.containsKey(name))
                    .toList();

            // (2) orphan: still active on the cell, gone from the master. An on-market cell is informational
            //     only — propagation refuses to switch those off (screen would desync from the marketplace).
            List<String> orphans = new ArrayList<>();
            List<String> marketOrphans = new ArrayList<>();
            for (ProductListingOption option : cellByName.values()) {
                if (masterByName.containsKey(option.getOptionName())
                        || !Boolean.TRUE.equals(option.getActive())) {
                    continue;   // still owned by the master, or already off → propagation writes nothing
                }
                (onMarket ? marketOrphans : orphans).add(option.getOptionName());
            }

            // (3) quantities: matched options only, shared productIds only, active-agnostic.
            List<String> quantityMismatches = new ArrayList<>();
            for (Map.Entry<String, ProductListingOption> entry : cellByName.entrySet()) {
                Map<Long, Integer> masterQuantities = masterByName.get(entry.getKey());
                if (masterQuantities == null) {
                    continue;   // unmatched option → syncOptionQuantities skips it
                }
                Map<Long, Integer> cellQuantities =
                        cellQuantitiesByOption.getOrDefault(entry.getValue().getId(), Map.of());
                boolean differs = cellQuantities.entrySet().stream().anyMatch(line -> {
                    Integer masterQuantity = masterQuantities.get(line.getKey());
                    return masterQuantity != null && !masterQuantity.equals(line.getValue());
                });
                if (differs) {
                    quantityMismatches.add(entry.getKey());
                }
            }

            boolean fixable = !missing.isEmpty() || !orphans.isEmpty() || !quantityMismatches.isEmpty();
            if (!fixable && marketOrphans.isEmpty()) {
                continue;   // nothing to show for this channel
            }
            channels.add(ChannelSyncPreviewResponse.Channel.builder()
                    .listingId(cell.getId())
                    .sellerName(sellerNames.get(cell.getSeller().getId()))
                    .platform(cell.getPlatform())
                    .onMarket(onMarket)
                    .missingOptions(missing)
                    .orphanOptions(orphans)
                    .marketOrphanOptions(marketOrphans)
                    .quantityMismatchOptions(quantityMismatches)
                    .build());
            if (fixable) {
                // Market-only orphan channels are listed but never counted — counting them would leave
                // inSync=false (and the button lit) for ever, since propagation cannot clear them.
                affectedChannels++;
                missingTotal += missing.size();
                orphanTotal += orphans.size();
                quantityTotal += quantityMismatches.size();
            }
        }

        channels.sort(Comparator
                .comparing(ChannelSyncPreviewResponse.Channel::getSellerName,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ChannelSyncPreviewResponse.Channel::getPlatform,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        return ChannelSyncPreviewResponse.builder()
                .inSync(affectedChannels == 0)
                .totals(ChannelSyncPreviewResponse.Totals.builder()
                        .affectedChannels(affectedChannels)
                        .missingOptions(missingTotal)
                        .orphanOptions(orphanTotal)
                        .quantityMismatch(quantityTotal)
                        .build())
                .channels(channels)
                .build();
    }

    /** No propagatable cell → nothing [일괄 반영] could change. */
    private ChannelSyncPreviewResponse emptyPreview() {
        return ChannelSyncPreviewResponse.builder()
                .inSync(true)
                .totals(ChannelSyncPreviewResponse.Totals.builder().build())
                .channels(List.of())
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
        // reached), not just by @Transactional rollback.
        // 84: a master always has at least one option (the "master-only" backward-compat allowance is gone).
        // Pre-existing option-less masters are NOT migrated — only the create and delete paths are closed.
        List<MasterOptionRequest> options = request.getOptions();
        if (options == null || options.isEmpty()) {
            throw new ValidationException("옵션을 1개 이상 등록하세요.");
        }
        // 86: option names are unique within a master (assertNameUnique guards the create/update-one paths;
        // this closes the same rule for the array posted at master creation). Same message, same trim rule.
        Set<String> optionNames = new LinkedHashSet<>();
        for (MasterOptionRequest option : options) {
            assertCoversComponents(componentIds, toVector(option), "옵션은 구성상품 전체를 포함해야 합니다");
            if (!optionNames.add(option.getName() == null ? null : option.getName().trim())) {
                throw new ValidationException("같은 이름의 옵션이 이미 있습니다.");
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

        for (MasterOptionRequest option : options) {
            persistOption(saved, option, componentIds);
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
    public MasterProductResponse updateRegistrationNameSuffix(Long id, OptionCheckSuffixRequest request) {
        MasterProduct master = requireScopedMaster(id);
        MasterProduct updated = masterProductRepository.save(master.toBuilder()
                .optionCheckSuffixEnabled(request.getEnabled())
                .optionCheckSuffix(normalizeSuffix(request.getSuffix()))
                .build());
        return mapToResponse(updated);
    }

    /** blank → null (inherit); else trimmed. Shared normalization for the 69 suffix text. */
    private static String normalizeSuffix(String suffix) {
        return (suffix == null || suffix.isBlank()) ? null : suffix.trim();
    }

    @Override
    @Transactional
    public MasterProductResponse updateShippingOverride(Long id, Map<String, String> override) {
        MasterProduct master = requireScopedMaster(id);
        // Key whitelist only (no value validation — register 72/73 is the final guard). Master whitelist ⊂
        // listing whitelist: place keys (outbound/return center) are silently dropped (account-specific, 75).
        // null/empty (after filtering) = no override.
        MasterProduct updated = masterProductRepository.save(
                master.toBuilder().shippingOverride(ShippingOverrideKeys.filterMaster(override)).build());
        return mapToResponse(updated);
    }

    @Override
    @Transactional
    public int applyShippingOverrideToChannels(Long id, List<Long> listingIds) {
        MasterProduct master = requireScopedMaster(id);
        // 79: the master's settings are WRITTEN ONTO each selected cell (overwrite), not just cleared —
        // afterwards the cell's shipping settings are exactly the master's, and the cell owns them.
        // Master-level keys the master leaves empty are removed from the cell (so the cell matches the master
        // and those fields fall through to the account default). Place keys (outbound / return center) are the
        // account's own registered centers — never touched.
        Map<String, String> masterOverride = ShippingOverrideKeys.filterMaster(master.getShippingOverride());
        List<ProductListing> targets = selectChannels(id, listingIds);

        List<ProductListing> changed = new ArrayList<>();
        for (ProductListing cell : targets) {
            Map<String, String> current = cell.getShippingOverride();
            Map<String, String> next = new LinkedHashMap<>();
            if (current != null) {
                for (Map.Entry<String, String> entry : current.entrySet()) {
                    if (ShippingOverrideKeys.PLACE_KEYS.contains(entry.getKey())) {
                        next.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            if (masterOverride != null) {
                next.putAll(masterOverride);
            }
            Map<String, String> resolved = next.isEmpty() ? null : next;
            if (Objects.equals(resolved, current)) {
                continue; // already exactly the master's settings → idempotent no-op, not counted
            }
            changed.add(cell.toBuilder().shippingOverride(resolved).build());
        }
        if (!changed.isEmpty()) {
            productListingRepository.saveAll(changed);
        }
        return changed.size();
    }

    /**
     * The channels to force-apply to: all of this master's cells, or just the requested subset (79). An id
     * outside this master's channels is a client bug — 400 rather than a silent skip that would read as a
     * successful apply.
     */
    private List<ProductListing> selectChannels(Long masterId, List<Long> listingIds) {
        List<ProductListing> cells = productListingRepository.findByMasterProductId(masterId);
        if (listingIds == null || listingIds.isEmpty()) {
            return cells;
        }
        Set<Long> requested = new LinkedHashSet<>(listingIds);
        Set<Long> owned = cells.stream().map(ProductListing::getId).collect(Collectors.toSet());
        Set<Long> unknown = new LinkedHashSet<>(requested);
        unknown.removeAll(owned);
        if (!unknown.isEmpty()) {
            throw new ValidationException("이 마스터의 채널이 아닌 항목이 포함되었습니다: " + unknown);
        }
        return cells.stream().filter(cell -> requested.contains(cell.getId())).toList();
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
        assertNameUnique(masterId, request.getName(), null);
        MasterProductOption option = persistOption(master, request, componentProductIds(masterId));
        // 86: the master is the option universe — an added option belongs on every channel of this master
        // (switched off there; see MasterOptionChannelSync). Same transaction, so a failed sync rolls the
        // option back rather than leaving the channels behind.
        masterOptionChannelSync.onOptionCreated(masterId, option);
        // A brand-new option can never be on the market, but run the same judgement rather than hard-coding
        // false — one rule, one code path.
        Set<String> lockedNames = marketRegisteredOptionNames(List.of(masterId)).getOrDefault(masterId, Set.of());
        return mapToOptionResponse(option, toVector(request), lockedNames.contains(option.getName()));
    }

    @Override
    @Transactional
    public MasterOptionResponse updateOption(Long masterId, Long optionId, MasterOptionRequest request) {
        requireScopedMaster(masterId);
        MasterProductOption option = requireOption(masterId, optionId);

        // ⚠️ Capture the pre-edit state FIRST — before deleteByOptionId wipes the item rows. The lock guard,
        // the "did the quantities actually change?" test and the cell-option match key all read it. Reading
        // it after the delete would always report "changed": saving a locked option would 400 even when
        // nothing moved, and every save would re-price every channel.
        String oldName = option.getName();
        Map<Long, Integer> oldVector = optionItemRepository.findByOptionId(optionId).stream()
                .collect(Collectors.toMap(it -> it.getProduct().getId(), MasterProductOptionItem::getQuantity));

        Set<String> lockedNames = marketRegisteredOptionNames(List.of(masterId)).getOrDefault(masterId, Set.of());
        boolean locked = lockedNames.contains(oldName);
        boolean renamed = !Objects.equals(oldName, request.getName());
        // Sending the same items back is allowed — the frontend posts the whole form, so "identical = blocked"
        // would 400 an edit that only touched the delivery/box override.
        Map<Long, Integer> newVector = request.getItems() != null ? toVector(request) : null;
        boolean quantitiesChanged = newVector != null && !newVector.equals(oldVector);

        if (locked && renamed) {
            throw new ValidationException("쿠팡에 등록된 옵션은 이름을 바꿀 수 없습니다.");
        }
        if (locked && quantitiesChanged) {
            throw new ValidationException("쿠팡에 등록된 옵션은 수량을 바꿀 수 없습니다.");
        }
        if (renamed) {
            assertNameUnique(masterId, request.getName(), optionId);
        }

        Map<Long, Integer> vector;
        if (newVector != null) {
            vector = newVector;
            assertCoversComponents(componentProductIds(masterId), vector, "옵션은 구성상품 전체를 포함해야 합니다");
            optionItemRepository.deleteByOptionId(optionId);
            saveItems(option, vector);
        } else {
            vector = oldVector;
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

        resyncChannels(masterId, updated, oldName, renamed, quantitiesChanged);
        // Reuse the judgement computed above — re-running the helper would double the lock queries per save.
        return mapToOptionResponse(updated, vector, locked);
    }

    @Override
    @Transactional
    public void deleteOption(Long masterId, Long optionId) {
        requireScopedMaster(masterId);
        MasterProductOption option = requireOption(masterId, optionId);

        // Order matters: the more specific message wins when both apply. Deleting a market-registered option
        // is blocked outright — otherwise "delete then re-add" would be a way around the edit lock, while
        // Coupang keeps the approved option that can no longer be removed there.
        Set<String> lockedNames = marketRegisteredOptionNames(List.of(masterId)).getOrDefault(masterId, Set.of());
        if (lockedNames.contains(option.getName())) {
            throw new ValidationException("쿠팡에 등록된 옵션은 삭제할 수 없습니다. 판매 중지 후 마켓에서 정리하세요.");
        }
        // A master always keeps at least one option; dropping them all means deleting the master.
        if (optionRepository.findByMasterProductId(masterId).size() <= 1) {
            throw new ValidationException("옵션은 1개 이상 있어야 합니다. 모두 없애려면 마스터를 삭제하세요.");
        }

        // 86: switch the option off on every channel BEFORE the master row (and its name, the only match
        // key) is gone. Rows are kept — see MasterOptionChannelSync for why deletion is never cascaded.
        masterOptionChannelSync.onOptionRemoved(masterId, option.getName());

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

    // ---------------------------------------------------------------- market lock (84)

    /**
     * Which option names of each master are <b>locked</b> because they are live on a marketplace
     * (FEATURE_2608_06 / 84). A locked option may not be renamed, re-quantified or deleted: the product
     * already exists on Coupang, where an option change means a full re-submission + re-approval and an
     * approved option cannot be removed at all — that cleanup happens outside this system.
     *
     * <p>An option counts as market-registered when it sits on a cell that reached the market
     * ({@code platformProductId != null}) AND <b>any</b> of these holds:</p>
     * <ol>
     *   <li>{@code active} — it goes out in the next push payload;</li>
     *   <li>{@code platformOptionId != null} — Coupang issued a vendorItemId, so it exists there;</li>
     *   <li>{@code approvalStatus == APPROVED} — it was approved at some point.</li>
     * </ol>
     * <p>The last two terms are shared with 87's uncheck guard via {@link ProductListingOption#isMarketRegistered()}
     * — 84 is the superset that adds {@code active} on top.</p>
     * ⚠️ All three are needed. An option switched off locally still lives on Coupang (approved options
     * cannot be deleted there), so {@code active} alone would let it slip out of the lock; and
     * {@code platformOptionId} / {@code approvalStatus} are filled by {@code fetchStatus}, so they are still
     * null when nobody refreshed the status after a push.
     *
     * <p>⚠️ Exactly two queries regardless of how many masters are asked for — judging masters one at a time
     * would be an N+1 on the list endpoint.</p>
     */
    private Map<Long, Set<String>> marketRegisteredOptionNames(Collection<Long> masterIds) {
        if (masterIds == null || masterIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> masterByCell = productListingRepository.findByMasterProductIdIn(masterIds).stream()
                .filter(cell -> cell.getPlatformProductId() != null)
                .collect(Collectors.toMap(
                        ProductListing::getId, cell -> cell.getMasterProduct().getId(), (first, dup) -> first));
        if (masterByCell.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<String>> lockedByMaster = new LinkedHashMap<>();
        for (ProductListingOption option : productListingOptionRepository
                .findByProductListingIdIn(masterByCell.keySet())) {
            if (!isOnMarket(option)) {
                continue;
            }
            Long masterId = masterByCell.get(option.getProductListing().getId());
            if (masterId != null) {
                lockedByMaster.computeIfAbsent(masterId, key -> new LinkedHashSet<>())
                        .add(option.getOptionName());
            }
        }
        return lockedByMaster;
    }

    /** The three-way OR above, for one channel option of an already market-registered cell. */
    private static boolean isOnMarket(ProductListingOption option) {
        // 84 = 87's two terms + active; the shared pair lives in ProductListingOption#isMarketRegistered.
        return Boolean.TRUE.equals(option.getActive()) || option.isMarketRegistered();
    }

    /**
     * Option names are unique within a master. Every match map here is {@code (first, dup) -> first}, so
     * same-named options would make master↔channel matching non-deterministic; it would also hand a user
     * who cannot rename a locked option a way around the lock by adding a second option with that name.
     *
     * @param excludeOptionId the option being edited (skipped), or null on create
     */
    private void assertNameUnique(Long masterId, String name, Long excludeOptionId) {
        String candidate = name == null ? null : name.trim();
        boolean taken = optionRepository.findByMasterProductId(masterId).stream()
                .filter(existing -> !existing.getId().equals(excludeOptionId))
                .anyMatch(existing -> existing.getName() != null
                        && existing.getName().trim().equals(candidate));
        if (taken) {
            throw new ValidationException("같은 이름의 옵션이 이미 있습니다.");
        }
    }

    /**
     * 84 Step 4 — narrow channel re-sync after an unlocked option was edited: cascade a rename onto the
     * cells, then (only when the quantity vector actually moved) push the new quantities down the matched
     * BOM lines and recompute that cell's option prices.
     *
     * <p>Prices are the only derived value a quantity change touches, so the thumbnail and detail HTML are
     * deliberately NOT regenerated: {@code regenerateAssets} would cost an S3 GET + Java2D render + S3 PUT
     * per cell (plus every zone image when a processing preset is attached) for one edited option row.</p>
     *
     * <p>⚠️ {@code needsMarketSync} is deliberately NOT raised: by the lock rule an editable option is, on
     * every market-registered cell, inactive AND without a market id AND never approved — so it is not in
     * that cell's next push payload anyway. Nothing changed that the market can see.</p>
     */
    private void resyncChannels(Long masterId, MasterProductOption updated,
                                String oldName, boolean renamed, boolean quantitiesChanged) {
        if (!renamed && !quantitiesChanged) {
            return;     // name/delivery/box-only edits change nothing downstream
        }
        if (renamed) {
            // 86: the cascade moved to the shared structure-sync component (one implementation, also used
            // by option create/delete and propagation).
            masterOptionChannelSync.onOptionRenamed(masterId, oldName, updated.getName());
        }
        if (!quantitiesChanged) {
            return;
        }
        // ⚠️ Match on the option's CURRENT name: after the cascade above the cell options already carry the
        // new one, so looking them up by oldName would silently match nothing.
        String currentName = renamed ? updated.getName() : oldName;
        for (ProductListing cell : productListingRepository.findByMasterProductId(masterId)) {
            List<ProductListingOption> matched = productListingOptionRepository
                    .findByProductListingId(cell.getId()).stream()
                    .filter(cellOption -> currentName.equals(cellOption.getOptionName()))
                    .toList();
            if (matched.isEmpty()) {
                continue;   // this channel does not carry the option → nothing to re-sync
            }
            matched.forEach(cellOption -> optionQuantitySync.syncLines(cellOption, updated));
            // Same transaction: the lines saved just above are visible to the cost sum via JPA auto-flush.
            listingAssetService.recalculateOptionPrices(cell);
        }
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

    /** Single-master convenience: judges the lock for this master alone (2 queries). */
    private MasterProductResponse mapToResponse(MasterProduct master) {
        Set<String> lockedNames = marketRegisteredOptionNames(List.of(master.getId()))
                .getOrDefault(master.getId(), Set.of());
        return mapToResponse(master, lockedNames);
    }

    /**
     * @param lockedNames option names of this master that are live on a marketplace (84) — the list path
     *                    passes its batched judgement so the flag is never quietly reported as false
     */
    private MasterProductResponse mapToResponse(MasterProduct master, Set<String> lockedNames) {
        Long id = master.getId();
        List<MasterProductComponent> components = componentRepository.findByMasterProductId(id);
        List<MasterProductOption> options = optionRepository.findByMasterProductId(id);
        List<Long> optionIds = options.stream().map(MasterProductOption::getId).toList();
        List<MasterProductOptionItem> items = optionIds.isEmpty()
                ? List.of() : optionItemRepository.findByOptionIdIn(optionIds);

        // Batch every referenced product in one query (N+1 guard). Read product fields from THIS map,
        // never from c.getProduct().getX() — that initialises the lazy proxy (open-in-view: false).
        Set<Long> productIds = new LinkedHashSet<>();
        components.forEach(c -> productIds.add(c.getProduct().getId()));
        items.forEach(it -> productIds.add(it.getProduct().getId()));
        Map<Long, Product> productsById = productIds.isEmpty()
                ? Map.of()
                : productRepository.findAllById(productIds).stream()
                        .collect(Collectors.toMap(Product::getId, p -> p));

        List<MasterProductResponse.Component> componentResponses = components.stream()
                .map(c -> {
                    Product p = productsById.get(c.getProduct().getId());
                    return MasterProductResponse.Component.builder()
                            .productId(c.getProduct().getId())
                            // p == null: the product row is gone (orphan component) — keep the pre-100
                            // behaviour of reporting nulls instead of blowing the whole response up.
                            .productName(p == null ? null : p.getProductName())
                            .netContent(p == null ? null : p.getNetContent())
                            .netContentUnit(p == null ? null : p.getNetContentUnit())
                            .build();
                })
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
                        .marketRegistered(lockedNames.contains(o.getName()))
                        .items(itemsByOption.getOrDefault(o.getId(), List.of()).stream()
                                .map(it -> {
                                    Product p = productsById.get(it.getProduct().getId());
                                    return MasterOptionResponse.Item.builder()
                                            .productId(it.getProduct().getId())
                                            .productName(p == null ? null : p.getProductName())
                                            .quantity(it.getQuantity())
                                            .build();
                                })
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
                // 69: pure fields (no N+1) → filled on both the list and single-fetch paths for prefill.
                .optionCheckSuffixEnabled(master.getOptionCheckSuffixEnabled())
                .optionCheckSuffix(master.getOptionCheckSuffix())
                .shippingOverride(master.getShippingOverride())
                .components(componentResponses)
                .options(optionResponses)
                .build();
    }

    /** create/update single-option response (the list/read path builds its options inline instead). */
    private MasterOptionResponse mapToOptionResponse(MasterProductOption option, Map<Long, Integer> vector,
                                                     boolean marketRegistered) {
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
                .marketRegistered(marketRegistered)
                .items(items)
                .build();
    }

    private static String matchKey(Long sellerId, String platform) {
        return sellerId + "|" + platform;
    }
}
