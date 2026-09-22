package com.pms.service;

import com.pms.domain.CarrierRate;
import com.pms.domain.Category;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.GeneratedContentSource;
import com.pms.domain.MasterImageZoneAssignment;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Package;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.Seller;
import com.pms.dto.request.MasterCategoryRequest;
import com.pms.dto.request.MasterCompositionRequest;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductQuery;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.request.MasterProductUpdateRequest;
import com.pms.dto.request.OptionCheckSuffixRequest;
import com.pms.dto.response.ApplyOptionNamesResponse;
import com.pms.dto.response.ChannelSyncPreviewResponse;
import com.pms.dto.response.ListingMatrixResponse;
import com.pms.dto.response.ListingMatrixResponse.MatrixCell;
import com.pms.dto.response.ListingMatrixResponse.MatrixRow;
import com.pms.dto.response.ListingOptionsResponse;
import com.pms.dto.response.MasterCategoryResponse;
import com.pms.dto.response.MasterChannelOptionsResponse;
import com.pms.dto.response.MasterOptionResponse;
import com.pms.dto.response.MasterProductByComponentsResponse;
import com.pms.dto.response.MasterProductResponse;
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
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.listing.CellBomResolver;
import com.pms.service.listing.ListingStockPolicy;
import com.pms.service.listing.MasterOptionChannelSync;
import com.pms.service.listing.MasterPropagationService;
import com.pms.service.listing.OptionCheckSuffix;
import com.pms.service.listing.TagMergeService;
import com.pms.service.listing.shipping.ShippingOverrideKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    /** 셀 옵션의 구성품은 마스터를 타고 얻는다(2609_71). */
    private final CellBomResolver cellBomResolver;
    private final GeneratedProductDataRepository generatedProductDataRepository;
    private final MasterImageZoneAssignmentRepository masterImageZoneAssignmentRepository;
    private final SellerRepository sellerRepository;
    private final ImageStorageService imageStorageService;
    private final ImageValidator imageValidator;
    private final MasterPropagationService masterPropagationService;
    private final MasterOptionChannelSync masterOptionChannelSync;
    private final ListingAssetService listingAssetService;
    private final TagMergeService tagMergeService;
    private final RegistrationNameGenerator registrationNameGenerator;
    private final OptionCheckSuffixResolver optionCheckSuffixResolver;
    private final MasterChannelConfigService masterChannelConfigService;

    private static final String IMAGE_STORAGE_CATEGORY = "master";

    /** Default page size when the client omits {@code size} (110). */
    private static final int DEFAULT_PAGE_SIZE = 25;
    /** Upper clamp for {@code size} — an oversized request is trimmed, not rejected (110 / D3). */
    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_SORT = "createdAt,desc";
    /**
     * Sort whitelist: request key → entity property (110 / D2). A client string is never handed to
     * {@code Sort.by} directly (it would leak/expose internal property names and blow up at query time).
     * <b>Extension point</b>: a new sort key is one entry here.
     */
    private static final Map<String, String> SORT_FIELDS = Map.of("createdAt", "createdAt");

    // ---------------------------------------------------------------- reads

    @Override
    public Page<MasterProductResponse> getMasterProducts(MasterProductQuery query) {
        Pageable pageable = PageRequest.of(
                Math.max(query.getPage(), 0), normalizeSize(query.getSize()), parseSort(query.getSort()));
        String keyword = query.getSearch() == null ? null : query.getSearch().trim();

        Page<MasterProduct> masters = (keyword == null || keyword.isEmpty())
                ? masterProductRepository.findAllScoped(pageable)
                : masterProductRepository.searchPage(keyword, pageable);
        // Overlay + lock judgement run on the PAGE CONTENT only — never load everything and sub-list.
        List<Long> ids = masters.getContent().stream().map(MasterProduct::getId).toList();
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
        return masters.map(master -> {
            MasterProductResponse response = mapToResponse(
                    master, lockedByMaster.getOrDefault(master.getId(), Set.of()));
            String cover = coverByMaster.get(master.getId());
            return cover != null ? response.toBuilder().sourceImageUrl(cover).build() : response;
        });
    }

    /** {@code size <= 0} (= omitted) → 25, {@code > 100} → 100. Never a 400 (110 / D3). */
    private int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * {@code "field,direction"} → {@code Sort}, with {@code id} appended as a tie-breaker: rows sharing a
     * created_date (same-second creation, backfill) would otherwise repeat/vanish across page boundaries.
     * Backfilled masters may have a null created_date — MySQL orders NULLs first ASC / last DESC, and that
     * is accepted as-is (no COALESCE).
     */
    private Sort parseSort(String sort) {
        String raw = (sort == null || sort.isBlank()) ? DEFAULT_SORT : sort;
        String[] parts = raw.split(",");
        String key = parts[0].trim();
        String field = SORT_FIELDS.get(key);
        if (field == null) {
            throw new IllegalArgumentException(
                    "정렬 키가 올바르지 않습니다: " + key + " (허용: " + SORT_FIELDS.keySet() + ")");
        }
        Sort.Direction direction = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim()))
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, field).and(Sort.by(direction, "id"));
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

        // Right side: listings under this master (1 query), then their options batched (1 query) — reused for both
        // the selling price and each listing's active option set (drives the per-channel registration name).
        // 2609_22/D1: no master-option query here any more — the generator follows each option's FK.
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
        // 2609_22/D7: the registration name generator resolves the single-option case through the option's
        // master FK (falling back to the cell's own BOM), so it needs the option ROWS, not their names.
        Map<Long, List<ProductListingOption>> activeOptionsByListing = new LinkedHashMap<>();
        for (ProductListingOption o : allOptions) {
            if (Boolean.TRUE.equals(o.getActive())) {
                activeOptionsByListing
                        .computeIfAbsent(o.getProductListing().getId(), k -> new ArrayList<>())
                        .add(o);
            }
        }

        // 🔴 온보딩(2026-09-19): 한 계정에 셀이 <b>여럿</b>일 수 있다(같은 물건을 쿠팡 페이지 여러 개로 파는
        // 정상 판매 방식 — 2609_22/D18 부분 번복). 예전처럼 first-wins 로 인덱싱하면 두 번째 셀부터는
        // 응답에서 통째로 사라져 화면에서 열 수도, 가격을 볼 수도, 등록할 수도 없게 된다.
        Map<String, List<ProductListing>> listingsByKey = new LinkedHashMap<>();
        for (ProductListing pl : listings) {
            listingsByKey.computeIfAbsent(matchKey(pl.getSeller().getId(), pl.getPlatform()),
                    k -> new ArrayList<>()).add(pl);
        }

        // Left side: all accounts of the tenant + batched seller names (1 query).
        List<MarketplaceAccount> accounts = marketplaceAccountRepository.findAll();
        List<Long> sellerIds = accounts.stream()
                .map(a -> a.getSeller().getId())
                .distinct()
                .toList();
        Map<Long, String> sellerNames = sellerRepository.findAllById(sellerIds).stream()
                .collect(Collectors.toMap(Seller::getId, Seller::getSellerName));

        // (platform, code) → resolved category. One entry per distinct pair, not per cell.
        Map<String, MasterChannelConfigService.ChannelCategory> categoryCache = new LinkedHashMap<>();

        List<MatrixRow> rows = accounts.stream().map(acc -> {
            Long sellerId = acc.getSeller().getId();
            List<ProductListing> cellListings =
                    listingsByKey.getOrDefault(matchKey(sellerId, acc.getPlatform()), List.of());
            List<MatrixCell> cells = new ArrayList<>();
            for (ProductListing pl : cellListings) {
                // 67: registration name is always auto-generated per channel from this listing's active options.
                List<ProductListingOption> activeOptions =
                        activeOptionsByListing.getOrDefault(pl.getId(), List.of());
                // 69: suffix = channel(this row's account) ?? master ?? seller ?? system. Pure overload reuses the
                // already-loaded account (row) + seller (from the sellerNames findAllById, same session) + master
                // — NO per-cell account re-query (resolve(cell) would be N DB calls).
                OptionCheckSuffix suffix = optionCheckSuffixResolver.resolve(acc, master, acc.getSeller());
                // 2609_45/D9: the category this cell ACTUALLY uses + whether it is the channel's own.
                // Cached by (platform, code) — the master is fixed for this matrix, so the resolution is a
                // pure function of those two and would otherwise cost a lookup per cell.
                MasterChannelConfigService.ChannelCategory category = categoryCache.computeIfAbsent(
                        pl.getPlatform().name() + "|" + (pl.getPlatformCategoryCode() == null
                                ? "" : pl.getPlatformCategoryCode()),
                        key -> {
                            try {
                                return masterChannelConfigService.resolveChannelCategory(pl);
                            } catch (IllegalArgumentException e) {
                                // No standard category / no mapping — the matrix must still render.
                                return null;
                            }
                        });
                cells.add(MatrixCell.builder()
                        .productListingId(pl.getId())
                        .name(pl.getName())
                        .platformProductId(pl.getPlatformProductId())
                        .sellingPrice(priceByListing.get(pl.getId()))
                        .registrationName(registrationNameGenerator.generate(master, activeOptions, suffix))
                        .status(pl.getStatus() != null ? pl.getStatus().name() : null)
                        .categoryCode(category == null ? null : category.category().getCode())
                        .categoryName(category == null ? null : category.category().getName())
                        // ⚠️ never `platformCategoryCode != null` — see the field note (D10-1/D11).
                        .usesOwnCategory(category != null && category.own())
                        .build());
            }
            return MatrixRow.builder()
                    .sellerId(sellerId)
                    .sellerName(sellerNames.get(sellerId))
                    .platform(acc.getPlatform().name())
                    .accountId(acc.getId())
                    .accountLabel(acc.getAccountAlias())
                    .registered(!cells.isEmpty())
                    // `cell` 은 기존 화면 계약이라 첫 셀을 그대로 둔다. 전부 보려면 `cells` 를 읽는다.
                    .cell(cells.isEmpty() ? null : cells.get(0))
                    .cells(cells)
                    .build();
        }).toList();

        return ListingMatrixResponse.builder()
                .masterId(master.getId())
                .masterName(master.getName())
                // 2609_45/D13: the "A → B" confirmation needs the master side's name. Same for every cell,
                // so it sits at the top level rather than on each row.
                .masterCategoryName(master.getCategory() == null ? null : master.getCategory().getName())
                .rows(rows)
                .build();
    }

    /**
     * 2609_61/D6: every cell of the master + its options in one response — the screen draws an option×channel
     * table and must not fire one HTTP call (nor one query) per cell.
     *
     * <p>Query budget = 3 regardless of cell/option count: the master, the cells, their options batched
     * ({@code findByProductListingIdIn}), plus the master options used to resolve each option's stock ceiling.
     * ⚠️ {@code o.getProductListing().getId()} reads the FK id only — it does not wake the proxy. The cell's
     * {@code seller} is never touched (LAZY, and this response carries no channel label).</p>
     *
     * <p>No manual {@code tenant_id} condition: {@code ProductListingOption} has no {@code @TenantId}, but the
     * cell ids come from a tenant-scoped master's listings ({@code ProductListing} is {@code @TenantId}), so a
     * foreign tenant's option cannot structurally reach this list.</p>
     */
    @Override
    public MasterChannelOptionsResponse getChannelOptions(Long masterId) {
        MasterProduct master = requireScopedMaster(masterId);

        List<ProductListing> listings = productListingRepository.findByMasterProductId(masterId);
        if (listings.isEmpty()) {
            return MasterChannelOptionsResponse.builder()
                    .masterId(master.getId())
                    .cells(List.of())
                    .build();
        }

        List<Long> listingIds = listings.stream().map(ProductListing::getId).toList();
        Map<Long, List<ProductListingOption>> optionsByListing =
                productListingOptionRepository.findByProductListingIdIn(listingIds).stream()
                        .collect(Collectors.groupingBy(o -> o.getProductListing().getId(),
                                LinkedHashMap::new, Collectors.toList()));
        // 2609_22/D1: master options keyed by id — the single master↔channel matching axis.
        Map<Long, MasterProductOption> masterOptionsById = optionRepository.findByMasterProductId(masterId).stream()
                .collect(Collectors.toMap(MasterProductOption::getId, o -> o, (first, dup) -> first,
                        LinkedHashMap::new));

        List<MasterChannelOptionsResponse.CellOptions> cells = listings.stream()
                .map(pl -> MasterChannelOptionsResponse.CellOptions.builder()
                        .productListingId(pl.getId())
                        .platformProductId(pl.getPlatformProductId())
                        .status(pl.getStatus() != null ? pl.getStatus().name() : null)
                        .options(optionsByListing.getOrDefault(pl.getId(), List.of()).stream()
                                .map(o -> ListingOptionsResponse.OptionItem.from(o, linkedMasterOption(
                                        o, masterOptionsById)))
                                .toList())
                        .build())
                .toList();

        return MasterChannelOptionsResponse.builder()
                .masterId(master.getId())
                .cells(cells)
                .build();
    }

    /** The master option behind a cell option (FK id axis), or null for a channel-only option (2609_22/D2). */
    private MasterProductOption linkedMasterOption(ProductListingOption option,
                                                   Map<Long, MasterProductOption> byId) {
        // FK id only — safe on a LAZY proxy.
        MasterProductOption linked = option.getMasterProductOption();
        return linked == null ? null : byId.get(linked.getId());
    }

    /**
     * 89: read-only diff of every linked cell against the master — "what would [채널에 반영하기] change?".
     *
     * <p>⚠️ The judgement MUST mirror what propagation actually does, or the caller's banner never clears:</p>
     * <ul>
     *   <li>Cells with no {@code GeneratedProductData} are dropped entirely — {@code propagate} counts them
     *       {@code skipped} and never touches them, so a difference there is permanent.</li>
     *   <li>{@code missing}/{@code channelOnly} mirror {@code MasterOptionChannelSync.syncStructure} (1)/(2):
     *       matched by {@code master_product_option_id} (2609_22/D1); a channel-only option counts only while
     *       {@code active=true} (rows are never deleted, decision 42) and the cell is off-market — an on-market
     *       one is left alone by propagation (WARN only), so it is reported separately and never counted.</li>
     *   <li>2609_71: 셀의 구성품은 마스터 옵션을 타고 읽으므로({@code CellBomResolver}) 연결된 옵션의
     *       수량 차이는 구조적으로 생기지 않는다. 채널 전용 옵션만 구성품을 알 수 없어 빈 칸이 된다.</li>
     * </ul>
     *
     * <p>Query budget = one call per repository regardless of cell/option count (batched finders).</p>
     */
    @Override
    public ChannelSyncPreviewResponse previewChannelSync(Long masterId) {
        requireScopedMaster(masterId);

        // Master side: option name → (productId → quantity). Duplicate names / duplicate products: first wins
        // Duplicate names / duplicate products: first wins, so the preview can never disagree with propagation.
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
        // 2609_22/D1: keyed by master option id — the single master↔channel matching axis.
        Map<Long, Map<Long, Integer>> masterQuantitiesByOptionId = new LinkedHashMap<>();
        for (MasterProductOption option : masterOptions) {
            masterQuantitiesByOptionId.putIfAbsent(
                    option.getId(), masterItemsByOption.getOrDefault(option.getId(), Map.of()));
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
        // optionId → (productId → quantity); duplicate product lines: first wins.
        // 2609_71: 셀의 구성품도 마스터를 타고 읽는다 — 채널 전용 옵션은 구성품을 알 수 없어 빈 칸이 된다.
        Map<Long, Map<Long, Integer>> cellQuantitiesByOption = new LinkedHashMap<>();
        for (Map.Entry<Long, CellBomResolver.Bom> entry : cellBomResolver.forOptions(cellOptions).entrySet()) {
            Map<Long, Integer> quantities = new LinkedHashMap<>();
            for (CellBomResolver.Line line : entry.getValue().lines()) {
                quantities.putIfAbsent(line.productId(), line.quantity());
            }
            cellQuantitiesByOption.put(entry.getKey(), quantities);
        }
        // Linked options per cell, keyed by the master option they point at (D1); duplicates: first wins.
        Map<Long, Map<Long, ProductListingOption>> linkedByCell = new LinkedHashMap<>();
        // Channel-only options per cell (FK null, D2) — the master has nothing to compare them against.
        Map<Long, List<ProductListingOption>> channelOnlyByCell = new LinkedHashMap<>();
        for (ProductListingOption option : cellOptions) {
            Long cellId = option.getProductListing().getId();
            MasterProductOption linked = option.getMasterProductOption();
            if (linked == null) {
                channelOnlyByCell.computeIfAbsent(cellId, k -> new ArrayList<>()).add(option);
            } else {
                linkedByCell
                        .computeIfAbsent(cellId, k -> new LinkedHashMap<>())
                        .putIfAbsent(linked.getId(), option);
            }
        }

        // Seller names in ONE query (seller is LAZY + open-in-view=false: cell.getSeller().getSellerName()
        // would be a per-cell query, and a LazyInitializationException outside the transaction).
        List<Long> sellerIds = cells.stream().map(cell -> cell.getSeller().getId()).distinct().toList();
        Map<Long, String> sellerNames = sellerRepository.findAllById(sellerIds).stream()
                .collect(Collectors.toMap(Seller::getId, Seller::getSellerName, (first, dup) -> first));

        // Master option names, for the human-readable "missing" list (the matching itself is by id).
        Map<Long, String> masterNamesById = masterOptions.stream()
                .collect(Collectors.toMap(MasterProductOption::getId, MasterProductOption::getName,
                        (first, dup) -> first, LinkedHashMap::new));

        List<ChannelSyncPreviewResponse.Channel> channels = new ArrayList<>();
        int affectedChannels = 0, missingTotal = 0, channelOnlyTotal = 0, quantityTotal = 0;
        for (ProductListing cell : cells) {
            Map<Long, ProductListingOption> linked = linkedByCell.getOrDefault(cell.getId(), Map.of());
            boolean onMarket = cell.getPlatformProductId() != null;

            // (1) missing: master option with no row on this cell at all (active is irrelevant — the row's
            //     absence is what syncStructure fixes by creating it switched off).
            List<String> missing = masterNamesById.entrySet().stream()
                    .filter(entry -> !linked.containsKey(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .toList();

            // (2) channel-only: an option this master does not own. Since 2609_22/D22 this is one concept —
            //     an FK-less row (deliberate, D2) and a row whose master option was deleted (the FK is SET
            //     NULL) are the same thing. Counted only while still active; an on-market cell is
            //     informational only — propagation refuses to switch those off (screen would desync).
            List<String> channelOnly = new ArrayList<>();
            List<String> marketChannelOnly = new ArrayList<>();
            for (ProductListingOption option : channelOnlyByCell.getOrDefault(cell.getId(), List.of())) {
                if (!Boolean.TRUE.equals(option.getActive())) {
                    continue;   // already off → propagation writes nothing
                }
                (onMarket ? marketChannelOnly : channelOnly).add(option.getOptionName());
            }

            // (3) quantities: linked options only, shared productIds only, active-agnostic.
            List<String> quantityMismatches = new ArrayList<>();
            for (Map.Entry<Long, ProductListingOption> entry : linked.entrySet()) {
                Map<Long, Integer> masterQuantities = masterQuantitiesByOptionId.get(entry.getKey());
                if (masterQuantities == null) {
                    continue;   // linked to an option the master no longer has → syncOptionQuantities skips it
                }
                Map<Long, Integer> cellQuantities =
                        cellQuantitiesByOption.getOrDefault(entry.getValue().getId(), Map.of());
                boolean differs = cellQuantities.entrySet().stream().anyMatch(line -> {
                    Integer masterQuantity = masterQuantities.get(line.getKey());
                    return masterQuantity != null && !masterQuantity.equals(line.getValue());
                });
                if (differs) {
                    quantityMismatches.add(entry.getValue().getOptionName());
                }
            }

            boolean fixable = !missing.isEmpty() || !channelOnly.isEmpty() || !quantityMismatches.isEmpty();
            if (!fixable && marketChannelOnly.isEmpty()) {
                continue;   // nothing to show for this channel
            }
            channels.add(ChannelSyncPreviewResponse.Channel.builder()
                    .listingId(cell.getId())
                    .sellerName(sellerNames.get(cell.getSeller().getId()))
                    .platform(cell.getPlatform().name())
                    .onMarket(onMarket)
                    .missingOptions(missing)
                    .channelOnlyOptions(channelOnly)
                    .marketChannelOnlyOptions(marketChannelOnly)
                    .quantityMismatchOptions(quantityMismatches)
                    .build());
            if (fixable) {
                // Market-only channels are listed but never counted — counting them would leave
                // inSync=false (and the button lit) for ever, since propagation cannot clear them.
                affectedChannels++;
                missingTotal += missing.size();
                channelOnlyTotal += channelOnly.size();
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
                        .channelOnlyOptions(channelOnlyTotal)
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

    // ---------------------------------------------------------------- duplicate component set (2609_46)

    @Override
    public List<MasterProductByComponentsResponse> findByComponents(List<Long> productIds) {
        Set<Long> wanted = normaliseComponentIds(productIds);
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<MasterProduct> masters = findMastersWithComponentSet(wanted);
        if (masters.isEmpty()) {
            return List.of();
        }

        // N+1 guard: one query for every candidate's options (candidates are 0..a couple of rows).
        Map<Long, Long> optionCounts = optionRepository
                .findByMasterProductIdIn(masters.stream().map(MasterProduct::getId).toList()).stream()
                .collect(Collectors.groupingBy(o -> o.getMasterProduct().getId(), Collectors.counting()));

        return masters.stream()
                .sorted(Comparator.comparing(MasterProduct::getId))
                .map(m -> MasterProductByComponentsResponse.builder()
                        .id(m.getId())
                        .name(m.getName())
                        .optionCount(optionCounts.getOrDefault(m.getId(), 0L).intValue())
                        .build())
                .toList();
    }

    /** Deduped, null-free component ids (order preserved); the request order never affects matching. */
    private Set<Long> normaliseComponentIds(Collection<Long> productIds) {
        if (productIds == null) {
            return Set.of();
        }
        return productIds.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Masters whose component set equals {@code wanted} exactly (3 fixed queries, never N+1):
     * covers-all → same total count → tenant-scoped resolve. Superset/subset masters drop out at step 2,
     * other tenants' masters at step 3.
     */
    private List<MasterProduct> findMastersWithComponentSet(Set<Long> wanted) {
        List<Long> covering = componentRepository.findMasterIdsCoveringAll(wanted, wanted.size());
        if (covering.isEmpty()) {
            return List.of();
        }
        List<Long> exact = componentRepository.findMasterIdsWithComponentCount(covering, wanted.size());
        if (exact.isEmpty()) {
            return List.of();
        }
        return masterProductRepository.findScopedByIdIn(exact);
    }

    /**
     * MUST-KEEP: refuse to create a second master for a component set that already has one (2609_46).
     *
     * <p>The screen checks this before unlocking the rest of the form, but the screen is bypassable — this
     * is the final line. The message names the existing master so the user can go add an option to it.</p>
     *
     * <p>🔁 2609_72: masters are no longer hidden, so the named duplicate is always visible on the list
     * screen. The {@code active=false} suffix below can still fire for legacy rows written before that
     * change.</p>
     *
     * @param selfId the master being edited (2609_64/D7: keeping its own set is never a duplicate), or null
     *               on create. 🔴 One judgement function for both paths — a second one would drift.
     */
    private void assertComponentSetIsFree(Set<Long> componentIds, Long selfId) {
        List<MasterProduct> duplicates = findMastersWithComponentSet(componentIds).stream()
                .filter(m -> !m.getId().equals(selfId))   // 자기 자신은 중복이 아니다
                .toList();
        if (duplicates.isEmpty()) {
            return;
        }
        String names = duplicates.stream()
                .sorted(Comparator.comparing(MasterProduct::getId))
                .map(m -> m.getName() + "(id=" + m.getId() + ")"
                        + (Boolean.TRUE.equals(m.getActive()) ? "" : " — 삭제된 마스터"))
                .collect(Collectors.joining(", "));
        throw new ValidationException(
                "이 구성상품으로 만든 마스터가 이미 있습니다: " + names
                        + ". 새로 만들지 말고 그 마스터에 옵션을 추가하세요.");
    }

    @Override
    @Transactional
    public MasterProductResponse createMasterProduct(MasterProductRequest request) {
        List<Product> products = requireProducts(request.getComponentProductIds());
        Set<Long> componentIds = products.stream().map(Product::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 2609_46: identity first — the component set defines the master, so a set that already has a
        // master is rejected before anything else is judged (and before any save).
        assertComponentSetIsFree(componentIds, null);

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

    /**
     * Replace the component set and the full option list in one transaction (2609_64). Contract:
     * {@link MasterProductService#updateComposition}.
     *
     * <p>The order below is the contract: ① capture the current state → ② validate the whole request
     * (zero writes) → ③ replace the components → ④ delete the options the request dropped → ⑤ update the
     * kept ones → ⑥ create the new ones → ⑦ flag re-approval. A violation in ② throws before anything is
     * written, so a rejected request never leaves half a master behind.</p>
     */
    @Override
    @Transactional
    public MasterProductResponse updateComposition(Long id, MasterCompositionRequest request) {
        MasterProduct master = requireScopedMaster(id);

        // ⚠️ Capture the pre-edit state FIRST — same reason as updateOption: after the deletes below every
        // option would look "changed", so the lock guard and the "did the quantities move?" test must read
        // the state while it is still the old one.
        List<MasterProductOption> existing = optionRepository.findByMasterProductId(id);
        Map<Long, MasterProductOption> existingById = existing.stream()
                .collect(Collectors.toMap(MasterProductOption::getId, option -> option));
        List<Long> existingIds = existing.stream().map(MasterProductOption::getId).toList();
        Map<Long, Map<Long, Integer>> oldVectors = (existingIds.isEmpty()
                ? List.<MasterProductOptionItem>of()
                : optionItemRepository.findByOptionIdIn(existingIds)).stream()
                .collect(Collectors.groupingBy(it -> it.getOption().getId(),
                        Collectors.toMap(it -> it.getProduct().getId(), MasterProductOptionItem::getQuantity)));
        Set<String> lockedNames = marketRegisteredOptionNames(List.of(id)).getOrDefault(id, Set.of());

        // ---------------------------------------------------------------- validate (no writes)

        List<Product> products = requireProducts(request.getComponentProductIds());
        Set<Long> newComponentIds = products.stream().map(Product::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // 2609_64/D7: the duplicate-component guard applies here too — keeping our own set is not a duplicate.
        assertComponentSetIsFree(newComponentIds, id);

        Set<String> requestedNames = new LinkedHashSet<>();
        for (MasterCompositionRequest.OptionSpec spec : request.getOptions()) {
            // 86: option names are unique within a master — same message, same trim rule as createMasterProduct.
            if (!requestedNames.add(spec.getName() == null ? null : spec.getName().trim())) {
                throw new ValidationException("같은 이름의 옵션이 이미 있습니다.");
            }
            assertCoversComponents(newComponentIds, vectorOf(spec), "옵션은 구성상품 전체를 포함해야 합니다");
            if (spec.getOptionId() != null && !existingById.containsKey(spec.getOptionId())) {
                throw new ResourceNotFoundException("MasterProductOption", spec.getOptionId());
            }
        }
        // @NotEmpty already rejects an empty option list (400), so it is not re-counted here.

        Set<Long> keptIds = request.getOptions().stream()
                .map(MasterCompositionRequest.OptionSpec::getOptionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // 2609_64/D3: the market lock is NOT relaxed for this endpoint — deleting a locked option here would
        // be exactly the "delete then re-add" way around deleteOption's guard.
        for (MasterProductOption gone : existing) {
            if (!keptIds.contains(gone.getId()) && lockedNames.contains(gone.getName())) {
                throw new ValidationException("쿠팡에 등록된 옵션은 삭제할 수 없습니다. 판매 중지 후 마켓에서 정리하세요.");
            }
        }
        for (MasterCompositionRequest.OptionSpec spec : request.getOptions()) {
            if (spec.getOptionId() == null) {
                continue;
            }
            String oldName = existingById.get(spec.getOptionId()).getName();
            // ⚠️ A locked option's QUANTITIES stay editable (existing rule, updateOption) — only the name and
            // the delete are Coupang's to keep.
            if (!Objects.equals(oldName, spec.getName()) && lockedNames.contains(oldName)) {
                throw new ValidationException("쿠팡에 등록된 옵션은 이름을 바꿀 수 없습니다.");
            }
        }
        assertNoNameSwap(existing, request.getOptions());

        // ---------------------------------------------------------------- replace the component set

        // ⚠️ flush() after the delete: Hibernate's action queue runs INSERTs before entity DELETEs in one
        // flush, so re-inserting an unchanged (master, product) pair would collide with the not-yet-deleted
        // row on UQ_MPC (unique index). Forcing the delete first makes the re-insert safe.
        componentRepository.deleteByMasterProductId(id);
        componentRepository.flush();
        for (Product product : products) {
            componentRepository.save(MasterProductComponent.builder()
                    .masterProduct(master).product(product).build());
        }

        // ---------------------------------------------------------------- delete what the request dropped

        for (MasterProductOption gone : existing) {
            if (keptIds.contains(gone.getId())) {
                continue;
            }
            // 🔴 Switch the option off on every channel BEFORE the master row goes away — the FK is
            // ON DELETE SET NULL, so afterwards nothing points at it any more.
            masterOptionChannelSync.onOptionRemoved(id, gone.getId());
            optionItemRepository.deleteByOptionId(gone.getId());
            optionRepository.delete(gone);
        }

        // ---------------------------------------------------------------- update the kept options

        for (MasterCompositionRequest.OptionSpec spec : request.getOptions()) {
            if (spec.getOptionId() == null) {
                continue;
            }
            MasterProductOption option = existingById.get(spec.getOptionId());
            String oldName = option.getName();
            boolean renamed = !Objects.equals(oldName, spec.getName());
            Map<Long, Integer> newVector = vectorOf(spec);
            boolean quantitiesChanged = !newVector.equals(oldVectors.getOrDefault(option.getId(), Map.of()));

            optionItemRepository.deleteByOptionId(option.getId());
            saveItems(option, newVector);
            // 🔴 name ONLY — delivery/box/category meta/stock carry over untouched (2609_64/D5). stockQuantity
            // in particular means "null = clear" on the per-option path, so re-sending it here would wipe it.
            MasterProductOption updated = optionRepository.save(
                    option.toBuilder().name(spec.getName()).build());

            if (renamed) {
                masterOptionChannelSync.onOptionRenamed(id, updated.getId(), updated.getName());
            }
            if (quantitiesChanged) {
                // 2609_71: 셀에는 구성품 사본이 없다 — 이 훅은 바뀐 원가 합을 셀 판매가에 반영한다.
                masterOptionChannelSync.onOptionComponentsChanged(id, updated);
                markNeedsMarketSync(id, updated);
            }
        }

        // ---------------------------------------------------------------- create the new options

        for (MasterCompositionRequest.OptionSpec spec : request.getOptions()) {
            if (spec.getOptionId() != null) {
                continue;
            }
            // D6: a brand-new option carries name + quantities only; the rest stays unset and is filled in
            // afterwards from the existing option panel.
            MasterOptionRequest asRequest = MasterOptionRequest.builder()
                    .name(spec.getName()).items(spec.getItems()).build();
            MasterProductOption created = persistOption(master, asRequest, newComponentIds);
            masterOptionChannelSync.onOptionCreated(id, created);
        }

        // 🔴 masterPropagationService.propagate(id) is deliberately NOT called here (2609_64/D12): this method
        // just wrote three kinds of cell row (BOM, option price, needsMarketSync) and propagateOne runs
        // REQUIRES_NEW per cell — it would block on our uncommitted locks and read the pre-change composition.
        // The controller calls it after this transaction has committed.
        // clampChannelStocks is not called either — stock is untouched, so no ceiling moved.
        return mapToResponse(master);
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
        // clampedChannels = 0: a clamp can only happen when an existing master stock is lowered (update path).
        return mapToOptionResponse(option, toVector(request), lockedNames.contains(option.getName()), 0);
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
        // ⚠️ A locked option's QUANTITIES stay editable on purpose. The quantity vector is our own ledger
        // (cost, stock, price basis), not something the market owns, and a mistyped quantity at registration
        // time used to be unfixable: the option could not be edited, deleted, nor its cell/master removed.
        // The market-visible consequence (the 수량/계량 고시 text drifting from what Coupang shows) is handled
        // by resyncChannels raising needsMarketSync, which prompts [수정 요청] rather than blocking the fix.
        // The name and the delete guard stay locked — those are the ones Coupang cannot take back.
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
                // 102: stock follows the request as-is — null CLEARS it (back to unset), unlike the
                // delivery/box "null = keep" rule above. The option form always posts every field, and
                // emptying the stock box is a valid intent.
                .stockQuantity(request.getStockQuantity())
                .build());

        resyncChannels(masterId, updated, oldName, renamed, quantitiesChanged);
        // 102/D5: channel stock may not exceed the master's — lowering the master pulls the channels above it
        // down. Runs on every save (a stock-only edit changes neither name nor quantities), so it must sit
        // outside resyncChannels' early return.
        int clampedChannels = clampChannelStocks(masterId, updated);
        // Reuse the judgement computed above — re-running the helper would double the lock queries per save.
        return mapToOptionResponse(updated, vector, locked, clampedChannels);
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

        // 86: switch the option off on every channel BEFORE the master row is gone — the FK is ON DELETE
        // SET NULL (2609_22/D22), so afterwards nothing points at it. Rows are kept — see
        // MasterOptionChannelSync for why deletion is never cascaded.
        masterOptionChannelSync.onOptionRemoved(masterId, option.getId());

        optionItemRepository.deleteByOptionId(optionId);
        optionRepository.delete(option);
    }

    @Override
    @Transactional
    public ApplyOptionNamesResponse applyMasterOptionNames(Long masterId) {
        requireScopedMaster(masterId);
        Map<Long, String> masterNamesById = optionRepository.findByMasterProductId(masterId).stream()
                .collect(Collectors.toMap(MasterProductOption::getId, MasterProductOption::getName,
                        (first, dup) -> first));

        int updatedCells = 0, updatedOptions = 0;
        List<String> warnings = new ArrayList<>();
        for (ProductListing cell : productListingRepository.findByMasterProductId(masterId)) {
            List<ProductListingOption> options =
                    productListingOptionRepository.findByProductListingId(cell.getId());

            // Build the whole cell's post-reset view first: a channel-only option (D2) keeps its own name,
            // a linked one takes the master's. Nothing is saved until the cell passes the name check.
            List<ProductListingOption> toSave = new ArrayList<>();
            Set<String> resultingNames = new LinkedHashSet<>();
            boolean duplicate = false;
            for (ProductListingOption option : options) {
                MasterProductOption linked = option.getMasterProductOption();
                String masterName = linked == null ? null : masterNamesById.get(linked.getId());
                String resulting = masterName != null ? masterName : option.getOptionName();
                if (!resultingNames.add(resulting)) {
                    duplicate = true;
                    break;
                }
                boolean alreadyApplied = masterName == null
                        || (masterName.equals(option.getOptionName())
                            && option.getOptionNameSource() == GeneratedContentSource.AUTO);
                if (!alreadyApplied) {
                    toSave.add(option.toBuilder()
                            .optionName(masterName)
                            .optionNameSource(GeneratedContentSource.AUTO)
                            .build());
                }
            }
            if (duplicate) {
                // Skip this cell only — applying it would leave two options with the same Coupang itemName.
                warnings.add("옵션명 중복으로 건너뜀: listingId=" + cell.getId());
                continue;
            }
            if (toSave.isEmpty()) {
                continue;   // already applied → no write, not counted
            }
            productListingOptionRepository.saveAll(toSave);
            updatedCells++;
            updatedOptions += toSave.size();
        }
        return ApplyOptionNamesResponse.builder()
                .updatedCells(updatedCells)
                .updatedOptions(updatedOptions)
                .warnings(warnings)
                .build();
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
        if (!categoryMappingRepository.existsByCategoryIdAndPlatform(request.getCategoryId(), Platform.COUPANG)) {
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
     * (FEATURE_2608_06 / 84). A locked option may not be renamed or deleted: the product already exists on
     * Coupang, where a rename permanently breaks option matching and an approved option cannot be removed
     * at all — that cleanup happens outside this system.
     *
     * <p>⚠️ The quantity vector is deliberately NOT part of the lock. It is our own ledger (cost, stock,
     * price basis) rather than something the market owns, and locking it left a mistyped registration
     * quantity with no way back — the option could be neither edited, deleted, nor its cell or master
     * removed. The market-visible side effect is handled by {@link #resyncChannels} raising
     * {@code needsMarketSync}, not by refusing the edit.</p>
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
     * 🔴 2609_64: refuse an A↔B name swap inside one request — an option's NEW name may not equal
     * <b>another</b> existing option's CURRENT name, even when that other option gives the name up in the
     * same request (and even when it is being deleted here).
     *
     * <p>The per-option path cannot produce this (it edits one option at a time, so
     * {@link #assertNameUnique} covers it); this endpoint can, and the cell rename cascade would then hit
     * {@code onOptionRenamed}'s {@code newNameTaken} branch, log a WARN and skip — leaving the channel option
     * names out of step with the master. Saving it in two passes through a temporary name works.</p>
     */
    private void assertNoNameSwap(List<MasterProductOption> existing,
                                  List<MasterCompositionRequest.OptionSpec> specs) {
        Map<String, Long> ownerByCurrentName = new LinkedHashMap<>();
        for (MasterProductOption option : existing) {
            ownerByCurrentName.put(option.getName(), option.getId());
        }
        for (MasterCompositionRequest.OptionSpec spec : specs) {
            Long owner = ownerByCurrentName.get(spec.getName());
            if (owner != null && !owner.equals(spec.getOptionId())) {
                throw new ValidationException(
                        "옵션 이름을 서로 맞바꿀 수 없습니다. 임시 이름을 거쳐 두 번에 나눠 저장하세요.");
            }
        }
    }

    /**
     * 84 Step 4 — narrow channel re-sync after an option was edited: cascade a rename onto the cells, then
     * (only when the quantity vector actually moved) push the new quantities down the linked BOM lines,
     * recompute that cell's option prices and flag the market-registered cells for re-approval.
     *
     * <p>2609_22/D1: both steps match on {@code master_product_option_id}, so the order between them no
     * longer matters for correctness (it is kept for readability).</p>
     *
     * <p>Prices are the only derived value a quantity change touches, so the thumbnail and detail HTML are
     * deliberately NOT regenerated: {@code regenerateAssets} would cost an S3 GET + Java2D render + S3 PUT
     * per cell (plus every zone image when a processing preset is attached) for one edited option row.</p>
     *
     * <p>⚠️ {@code needsMarketSync} is raised only for a cell that is on the market AND actually carries
     * this option there: a quantity change rewrites the 수량 속성 / 계량 고시 text, so that cell now
     * disagrees with Coupang. An option that is off on the cell (or never reached the market) changes
     * nothing the market can see, so flagging it would put a permanent [수정 요청] badge on a clean cell.
     * The push itself is never automatic — the flag only surfaces the button.</p>
     */
    private void resyncChannels(Long masterId, MasterProductOption updated,
                                String oldName, boolean renamed, boolean quantitiesChanged) {
        if (!renamed && !quantitiesChanged) {
            return;     // name/delivery/box-only edits change nothing downstream
        }
        if (renamed) {
            // 86: the cascade moved to the shared structure-sync component (one implementation, also used
            // by option create/delete and propagation).
            masterOptionChannelSync.onOptionRenamed(masterId, updated.getId(), updated.getName());
        }
        if (!quantitiesChanged) {
            return;
        }
        // 2609_22/D1: match on the FK. The rename cascade above no longer matters here (an option keeps its
        // link whatever it is called), and a MANUAL_OVERRIDE cell name is matched just the same.
        for (ProductListing cell : productListingRepository.findByMasterProductId(masterId)) {
            List<ProductListingOption> matched = productListingOptionRepository
                    .findByProductListingId(cell.getId()).stream()
                    .filter(cellOption -> linkedTo(cellOption, updated.getId()))
                    .toList();
            if (matched.isEmpty()) {
                continue;   // this channel does not carry the option → nothing to re-sync
            }
            // 2609_71: 수량을 셀로 복사하는 단계는 사라졌다 — 셀 옵션은 FK 를 타고 마스터 옵션의 items 를
            // 그대로 읽는다. 바뀐 수량은 여기서 원가 합을 통해 판매가에만 반영하면 된다.
            listingAssetService.recalculateOptionPrices(cell);
            // A quantity change moves the 수량 속성 / 계량 고시 text, so a cell that already carries this
            // option on the market now disagrees with what Coupang shows. Mark it pending re-approval; the
            // push itself stays manual ([수정 요청]), per the no-auto-push rule.
            if (cell.getPlatformProductId() != null && matched.stream().anyMatch(MasterProductServiceImpl::isOnMarket)
                    && !cell.isNeedsMarketSync()) {
                productListingRepository.save(cell.toBuilder().needsMarketSync(true).build());
            }
        }
    }

    /**
     * 2609_64/D8: flag for re-approval only the cells that actually carry this option on the market. No push
     * — the flag just surfaces the [수정 요청] button (no-auto-push rule). Same condition as
     * {@link #resyncChannels}.
     *
     * <p>⚠️ Asset regeneration raises the same flag for its own cells ({@code propagateOne} step 4), but it
     * skips cells without generated assets — those are exactly the ones this helper still has to cover.</p>
     */
    private void markNeedsMarketSync(Long masterId, MasterProductOption option) {
        for (ProductListing cell : productListingRepository.findByMasterProductId(masterId)) {
            if (cell.getPlatformProductId() == null || cell.isNeedsMarketSync()) {
                continue;
            }
            boolean onMarket = productListingOptionRepository.findByProductListingId(cell.getId()).stream()
                    .filter(cellOption -> linkedTo(cellOption, option.getId()))
                    .anyMatch(MasterProductServiceImpl::isOnMarket);
            if (onMarket) {
                productListingRepository.save(cell.toBuilder().needsMarketSync(true).build());
            }
        }
    }

    /**
     * 102/D5: a channel's stock may never exceed the master's, so lowering the master pulls every channel
     * option that sits above the new ceiling down to it. Returns how many channel options were lowered.
     *
     * <p>⚠️ The change is deliberately NOT pushed to the market (no auto-push rule): the count travels back in
     * the response so the front can prompt [수정 요청]. The matching axis is {@code master_product_option_id}
     * (2609_22/D1) — the same one {@link #resyncChannels} uses; do not invent a second one.</p>
     */
    private int clampChannelStocks(Long masterId, MasterProductOption updated) {
        int ceiling = ListingStockPolicy.ceiling(updated);
        List<ProductListingOption> clamped = new ArrayList<>();
        for (ProductListing cell : productListingRepository.findByMasterProductId(masterId)) {
            productListingOptionRepository.findByProductListingId(cell.getId()).stream()
                    .filter(cellOption -> linkedTo(cellOption, updated.getId()))
                    .filter(cellOption -> cellOption.getStockQuantity() != null
                            && cellOption.getStockQuantity() > ceiling)
                    .forEach(cellOption -> clamped.add(cellOption.toBuilder().stockQuantity(ceiling).build()));
        }
        if (clamped.isEmpty()) {
            return 0;   // nothing above the ceiling → no save at all
        }
        productListingOptionRepository.saveAll(clamped);
        return clamped.size();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Is this cell option linked to that master option (2609_22/D1)? ⚠️ Reads the FK's id only — safe on a
     * LAZY proxy, so no extra query per option.
     */
    private static boolean linkedTo(ProductListingOption cellOption, Long masterOptionId) {
        MasterProductOption linked = cellOption.getMasterProductOption();
        return linked != null && linked.getId().equals(masterOptionId);
    }

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
     * {@code OptionSpec} items → (productId → quantity), following exactly the same rule as
     * {@link #toVector(MasterOptionRequest)} (a duplicate productId lets the LAST value win) — 2609_64.
     *
     * <p>🔴 One overload, never a copy of {@code toVector}'s body: the validation in {@code updateComposition}
     * and the vector actually persisted there must be produced by the same function.</p>
     */
    private Map<Long, Integer> vectorOf(MasterCompositionRequest.OptionSpec spec) {
        return spec.getItems().stream()
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
                .stockQuantity(request.getStockQuantity())              // null = unset → 9999 fallback (102)
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
                        // 102: without this the master detail response carries no stock and the option
                        // editor cannot prefill the existing value.
                        .stockQuantity(o.getStockQuantity())
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
                                                     boolean marketRegistered, int clampedChannels) {
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
                .stockQuantity(option.getStockQuantity())
                .clampedChannels(clampedChannels)
                .items(items)
                .build();
    }

    private static String matchKey(Long sellerId, Platform platform) {
        return sellerId + "|" + platform;
    }
}
