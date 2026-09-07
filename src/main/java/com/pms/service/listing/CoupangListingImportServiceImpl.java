package com.pms.service.listing;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductComponent;
import com.pms.domain.MasterProductOption;
import com.pms.domain.MasterProductOptionItem;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.Seller;
import com.pms.dto.request.ListingImportPreviewRequest;
import com.pms.dto.request.ListingImportRequest;
import com.pms.dto.response.ChannelAddResponse;
import com.pms.dto.response.ListingImportPreviewResponse;
import com.pms.exception.DuplicateChannelException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.ListingAssetService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 마켓 상품 가져오기 (FEATURE_2609_22 / D8~D20). See {@link CoupangListingImportService}.
 *
 * <p>구조는 {@code ChannelAddServiceImpl} 을 따른다(검증 → 셀 → 옵션·BOM → {@code regenerateAssets} → 응답).
 * ⚠️ 단 {@code REQUIRES_NEW} + self-proxy 는 복제하지 않았다 — 그 패턴은 배치가 셀마다 독립 커밋을 해야 해서
 * 있는 것이고, 가져오기는 단건이라 격리할 형제 트랜잭션이 없다(self 주입은 순환참조 위험만 남는다).</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CoupangListingImportServiceImpl implements CoupangListingImportService {

    private static final Logger log = LoggerFactory.getLogger(CoupangListingImportServiceImpl.class);

    /**
     * D15 고정 문구. 사용자가 정한 문장이므로 <b>임의로 바꾸지 말 것</b>(프론트가 이 문구를 그대로 띄운다).
     */
    static final String CATEGORY_WARNING =
            "정확한 카테고리 매핑이 안되어 마스터 프로덕트의 카테고리가 적용되었습니다. "
                    + "채널에 반영될 때까지 카테고리별 수수료 차이로 인한 마진 오차가 발생할 수 있습니다.";

    /**
     * {@link ListingChannel#fetchProduct} 를 실제로 구현한 플랫폼. ⚠️ 어댑터 기본 구현의
     * {@code UnsupportedOperationException} 은 전역 핸들러가 없어 500 이 되므로 여기서 400 으로 막는다.
     */
    private static final Set<Platform> SUPPORTED_PLATFORMS = Set.of(Platform.COUPANG);

    private final MasterProductRepository masterProductRepository;
    private final MasterProductComponentRepository masterProductComponentRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final MasterProductOptionItemRepository masterProductOptionItemRepository;
    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final ProductRepository productRepository;
    private final SellerRepository sellerRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final CategoryMappingRepository categoryMappingRepository;
    private final PlatformCategoryRepository platformCategoryRepository;
    private final ListingChannelResolver resolver;
    private final MasterOptionChannelSync masterOptionChannelSync;
    private final ListingAssetService listingAssetService;

    // ------------------------------------------------------------------ preview

    @Override
    public ListingImportPreviewResponse preview(Long masterProductId, ListingImportPreviewRequest request) {
        Platform platform = Platform.from(request.getPlatform());
        ImportContext ctx = validate(masterProductId, request.getSellerId(),
                platform, request.getPlatformProductId());
        ImportedProduct fetched = fetchProduct(ctx, request.getPlatformProductId());

        boolean matched = categoryMatches(ctx.master(), platform, fetched.categoryCode());
        return ListingImportPreviewResponse.builder()
                .productName(fetched.productName())
                .status(fetched.status())
                .categoryCode(fetched.categoryCode())
                .categoryMatched(matched)
                .categoryWarning(matched ? null : CATEGORY_WARNING)
                .channelTags(channelTags(ctx.master(), fetched.tags()))
                .components(previewComponents(ctx.components()))
                .options(fetched.options().stream()
                        .map(o -> ListingImportPreviewResponse.Option.builder()
                                .itemName(o.itemName())
                                .vendorItemId(o.vendorItemId())
                                .sellerProductItemId(o.sellerProductItemId())
                                .salePrice(o.salePrice())
                                .stockQuantity(o.stockQuantity())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    // ------------------------------------------------------------------ commit

    @Override
    @Transactional
    public ChannelAddResponse importListing(Long masterProductId, ListingImportRequest request) {
        // Defence in depth: a direct call may skip the preview entirely, so every preview guard runs again.
        Platform platform = Platform.from(request.getPlatform());
        ImportContext ctx = validate(masterProductId, request.getSellerId(),
                platform, request.getPlatformProductId());
        // The market is re-read here on purpose (D-commit): prices/options may have moved since the preview,
        // and the client's copy of them is never trusted.
        ImportedProduct fetched = fetchProduct(ctx, request.getPlatformProductId());

        Map<ListingImportRequest.OptionSpec, ImportedProduct.Option> pairs =
                matchOptions(request.getOptions(), fetched.options());
        // The master's components, keyed by id. ⚠️ Read the FK's id only — these are LAZY proxies, and they
        // are perfectly good FK targets for the rows written below (no extra query).
        Map<Long, Product> componentProducts = new LinkedHashMap<>();
        for (MasterProductComponent component : ctx.components()) {
            componentProducts.put(component.getProduct().getId(), component.getProduct());
        }
        Set<Long> componentIds = componentProducts.keySet();
        for (ListingImportRequest.OptionSpec spec : request.getOptions()) {
            assertCoversComponents(spec, componentIds);
        }

        // --- 1) master options first: creating one propagates to the master's OTHER cells (intended), and
        //        this cell does not exist yet, so it cannot be touched by that propagation.
        List<KnownOption> known = loadMasterOptions(masterProductId);
        Map<ListingImportRequest.OptionSpec, MasterProductOption> masterOptionBySpec = new LinkedHashMap<>();
        for (ListingImportRequest.OptionSpec spec : request.getOptions()) {
            masterOptionBySpec.put(spec, resolveMasterOption(ctx.master(), spec, known, componentProducts));
        }

        // --- 2) the cell itself
        boolean matched = categoryMatches(ctx.master(), platform, fetched.categoryCode());
        List<String> channelTags = channelTags(ctx.master(), fetched.tags());
        ProductListing cell = productListingRepository.save(ProductListing.builder()
                .masterProduct(ctx.master())
                .seller(ctx.seller())
                .platform(platform)
                .platformProductId(request.getPlatformProductId())
                // D16: the market's own leaf code, kept for display/compare only — never for the payload.
                .platformCategoryCode(fetched.categoryCode())
                // The market name is display data; a missing one would violate NOT NULL, so fall back.
                .name(fetched.productName() == null || fetched.productName().isBlank()
                        ? ctx.master().getName() : fetched.productName())
                // D19: the product is already live on the market — take its status, and it is not pending sync.
                .status(fetched.status())
                .needsMarketSync(false)
                // D17: null (not an empty list) means "no channel tags" — see the entity note.
                .tags(channelTags.isEmpty() ? null : channelTags)
                .build());

        // --- 3) cell options + 4) cell BOM
        for (ListingImportRequest.OptionSpec spec : request.getOptions()) {
            ImportedProduct.Option market = pairs.get(spec);
            ProductListingOption listingOption = productListingOptionRepository.save(ProductListingOption.builder()
                    .productListing(cell)
                    .masterProductOption(masterOptionBySpec.get(spec))     // FK, 01/D1
                    // D12: the market already shows this name — a master rename must not silently overwrite it.
                    .optionName(market.itemName())
                    .optionNameSource(GeneratedContentSource.MANUAL_OVERRIDE)
                    // D13: the real market price. MANUAL_OVERRIDE keeps regenerate from replacing it with the
                    // calculated one (guaranteed by 2609_19, not by 01) — see the note on regenerateAssets below.
                    .sellingPrice(market.salePrice())
                    .originalPrice(market.originalPrice())
                    .priceSource(GeneratedContentSource.MANUAL_OVERRIDE)
                    .stockQuantity(market.stockQuantity())
                    .platformOptionId(market.vendorItemId())
                    .sellerProductItemId(market.sellerProductItemId())
                    // D20: an id exists ⇒ Coupang approved it; without one it is still awaiting approval.
                    .approvalStatus(market.vendorItemId() != null
                            ? OptionApprovalStatus.APPROVED : OptionApprovalStatus.NOT_APPROVED)
                    .active(true)                                          // it is being sold on the market
                    .build());
            for (ListingImportRequest.Component component : spec.getComponents()) {
                productListingProductRepository.save(ProductListingProduct.builder()
                        .productListingOption(listingOption)
                        .product(componentProducts.get(component.getProductId()))
                        .quantity(component.getQuantity())
                        .build());
            }
        }
        // Flush so the reused seam reads the options/BOM we just wrote.
        productListingRepository.flush();

        // --- 5) thumbnail/detail from OUR master (D19). ⚠️ recalculateOptionPrices inside skips every
        //        MANUAL_OVERRIDE option, which is why the imported prices survive this call.
        listingAssetService.regenerateAssets(cell);

        return ChannelAddResponse.builder()
                .productListingId(cell.getId())
                .status(cell.getStatus().name())
                .generated(listingAssetService.getGenerated(cell.getId()))
                .categoryWarning(matched ? null : CATEGORY_WARNING)
                .build();
    }

    // ------------------------------------------------------------------ validation

    /**
     * 미리보기·커밋 공통 검증(Step 2 의 1~6). 마켓 조회 <b>전에</b> 끝나는 것들만 여기 있다 — 사용자가 수량을
     * 다 채운 뒤에 실패하면 안 되기 때문에 커밋도 같은 순서로 다시 돈다.
     */
    private ImportContext validate(Long masterProductId, Long sellerId, Platform platform, String platformProductId) {
        if (!SUPPORTED_PLATFORMS.contains(platform)) {
            throw new IllegalArgumentException(platform + " 가져오기 미지원");
        }

        MasterProduct master = masterProductRepository.findScopedById(masterProductId)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", masterProductId));
        if (Boolean.FALSE.equals(master.getActive())) {
            throw new IllegalArgumentException("비활성 마스터");
        }
        List<MasterProductComponent> components =
                masterProductComponentRepository.findByMasterProductId(masterProductId);
        if (components.isEmpty()) {
            throw new IllegalArgumentException("구성상품 없는 마스터");
        }

        // 🔴 Same guard as ChannelAddServiceImpl — without it the category judgement below NPEs.
        if (master.getCategory() == null) {
            throw new IllegalArgumentException("표준 카테고리 미설정");
        }
        // 🔴 The FORWARD mapping (master.category → CategoryMapping(platform) → PlatformCategory) is what the
        // payload and the price engine actually use, and it is a different lookup from the D14 reverse one.
        // Importing without it would still "succeed" (recalculateOptionPrices skips MANUAL_OVERRIDE options, so
        // PriceCalculator is never called) and then blow up later on margin display / [재생성] / [수정 요청].
        // Block the quietly-broken cell here instead. Reverse mismatch (D14) only warns; a missing forward
        // mapping blocks — do not merge the two.
        if (!categoryMappingRepository.existsByCategoryIdAndPlatform(master.getCategory().getId(), platform)) {
            throw new IllegalArgumentException(platform + " 카테고리 매핑 미설정");
        }

        Seller seller = sellerRepository.findById(sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Seller", sellerId));
        // Mirrors ListingRegistrationServiceImpl.resolveAccount, but keyed by (seller, platform) — this feature
        // has no cell yet to read them from.
        MarketplaceAccount account = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(sellerId, platform)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", sellerId));
        if (Boolean.FALSE.equals(account.getIsActive())) {
            throw new IllegalArgumentException("비활성 계정");
        }

        // D18: one market product page per account …
        if (productListingRepository.existsByMasterProductIdAndSellerIdAndPlatform(
                masterProductId, sellerId, platform)) {
            throw new DuplicateChannelException();          // 409 — 03 branches on this status code
        }
        // … and one cell per market product (stops the same Coupang product being attached to two masters).
        if (productListingRepository.existsByPlatformProductId(platformProductId)) {
            throw new IllegalArgumentException("이미 다른 상품에 연결된 쿠팡 상품입니다");
        }
        return new ImportContext(master, seller, account, components);
    }

    /** 마켓 조회 1회 + 응답 자체에 대한 검증(Step 2 의 7~8). */
    private ImportedProduct fetchProduct(ImportContext ctx, String platformProductId) {
        ImportedProduct fetched = resolver.resolve(ctx.account().getPlatform())
                .fetchProduct(platformProductId, ctx.account());
        if (fetched.options().isEmpty()) {
            throw new IllegalArgumentException("옵션 없는 쿠팡 상품입니다");
        }
        for (ImportedProduct.Option option : fetched.options()) {
            // A cell option without a price cannot be margin-checked, and NOT NULL would reject it anyway.
            if (option.salePrice() == null || option.salePrice().compareTo(BigDecimal.ZERO) == 0) {
                throw new IllegalArgumentException("판매가 없는 옵션: " + option.itemName());
            }
        }
        return fetched;
    }

    /**
     * 요청 옵션 집합 == 마켓 옵션 집합. 매칭 키는 {@code vendorItemId}, 없으면(미승인) {@code itemName}.
     *
     * <p>⚠️ id 없는 옵션끼리 이름이 겹치면 400 이다 — 임의로 첫 번째에 붙이면 구성이 통째로 뒤바뀐다.</p>
     */
    private Map<ListingImportRequest.OptionSpec, ImportedProduct.Option> matchOptions(
            List<ListingImportRequest.OptionSpec> specs, List<ImportedProduct.Option> marketOptions) {

        Map<String, ImportedProduct.Option> byVendorItemId = new HashMap<>();
        Map<String, ImportedProduct.Option> byName = new HashMap<>();
        Set<String> ambiguousNames = new HashSet<>();
        for (ImportedProduct.Option option : marketOptions) {
            if (option.vendorItemId() != null) {
                byVendorItemId.put(option.vendorItemId(), option);
            }
            if (option.itemName() != null && byName.put(option.itemName(), option) != null) {
                ambiguousNames.add(option.itemName());
            }
        }
        // The prompt's rule, checked up front: two not-yet-approved options sharing a name are unresolvable.
        for (ImportedProduct.Option option : marketOptions) {
            if (option.vendorItemId() == null && ambiguousNames.contains(option.itemName())) {
                throw new IllegalArgumentException("옵션명이 중복되어 식별할 수 없습니다: " + option.itemName());
            }
        }

        Map<ListingImportRequest.OptionSpec, ImportedProduct.Option> pairs = new LinkedHashMap<>();
        Set<ImportedProduct.Option> used = new HashSet<>();
        for (ListingImportRequest.OptionSpec spec : specs) {
            ImportedProduct.Option market;
            if (spec.getVendorItemId() != null) {
                market = byVendorItemId.get(spec.getVendorItemId());
            } else {
                if (ambiguousNames.contains(spec.getItemName())) {
                    throw new IllegalArgumentException("옵션명이 중복되어 식별할 수 없습니다: " + spec.getItemName());
                }
                market = byName.get(spec.getItemName());
            }
            if (market == null || !used.add(market)) {
                throw new IllegalArgumentException("쿠팡 옵션이 변경되었습니다 — 다시 조회하세요");
            }
            pairs.put(spec, market);
        }
        if (used.size() != marketOptions.size()) {
            throw new IllegalArgumentException("쿠팡 옵션이 변경되었습니다 — 다시 조회하세요");
        }
        return pairs;
    }

    /** D9: 옵션은 마스터 구성품을 <b>전부</b> 포함하고 모든 수량이 1 이상이어야 한다. */
    private void assertCoversComponents(ListingImportRequest.OptionSpec spec, Set<Long> componentIds) {
        Map<Long, Integer> vector = new LinkedHashMap<>();
        for (ListingImportRequest.Component component : spec.getComponents()) {
            vector.put(component.getProductId(), component.getQuantity());
        }
        List<Long> missing = componentIds.stream().filter(id -> !vector.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "옵션 '" + spec.getItemName() + "' 에 빠진 구성상품이 있습니다: " + missing);
        }
        List<Long> unknown = vector.keySet().stream().filter(id -> !componentIds.contains(id)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "옵션 '" + spec.getItemName() + "' 에 마스터에 없는 구성상품이 있습니다: " + unknown);
        }
        for (Map.Entry<Long, Integer> entry : vector.entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 1) {
                throw new IllegalArgumentException("옵션 '" + spec.getItemName()
                        + "' 의 수량은 1 이상이어야 합니다: 구성상품 " + entry.getKey());
            }
        }
    }

    // ------------------------------------------------------------------ master options (D10 / D11)

    /**
     * D10: 입력된 구성이 기존 마스터 옵션의 BOM 과 <b>완전히 같으면</b> 그 옵션을 쓰고, 아니면 새로 만든다.
     * "연결이냐 신규냐"를 사용자가 고르지 않고 구성이 결정한다.
     */
    private MasterProductOption resolveMasterOption(MasterProduct master,
                                                    ListingImportRequest.OptionSpec spec,
                                                    List<KnownOption> known,
                                                    Map<Long, Product> componentProducts) {
        Map<Long, Integer> vector = spec.getComponents().stream().collect(Collectors.toMap(
                ListingImportRequest.Component::getProductId,
                ListingImportRequest.Component::getQuantity,
                (first, dup) -> dup, LinkedHashMap::new));
        for (KnownOption candidate : known) {
            if (candidate.vector().equals(vector)) {
                return candidate.option();      // reuse; two cell options may legitimately share one master option
            }
        }
        // Names are unique within a master (existing invariant) — a same-named option with a DIFFERENT
        // composition means the user must rename it, so refuse rather than break that invariant.
        boolean nameTaken = known.stream().anyMatch(k -> k.option().getName().equals(spec.getMasterOptionName()));
        if (nameTaken) {
            throw new IllegalArgumentException("마스터 옵션명 중복: " + spec.getMasterOptionName());
        }

        MasterProductOption created = masterProductOptionRepository.save(MasterProductOption.builder()
                .masterProduct(master)
                .name(spec.getMasterOptionName())           // D11: defaults to the market's itemName
                .build());
        for (Map.Entry<Long, Integer> entry : vector.entrySet()) {
            masterProductOptionItemRepository.save(MasterProductOptionItem.builder()
                    .option(created)
                    .product(componentProducts.get(entry.getKey()))
                    .quantity(entry.getValue())
                    .build());
        }
        // Intended: a new master option is propagated to the master's other cells (inactive there).
        masterOptionChannelSync.onOptionCreated(master.getId(), created);
        // Visible to the remaining specs of this same request (two options may share one new composition).
        known.add(new KnownOption(created, vector));
        return created;
    }

    /** 마스터 옵션 + 그 BOM 벡터를 한 번에 읽어 둔다(옵션마다 재조회하지 않는다). */
    private List<KnownOption> loadMasterOptions(Long masterProductId) {
        List<MasterProductOption> options = masterProductOptionRepository.findByMasterProductId(masterProductId);
        List<Long> optionIds = options.stream().map(MasterProductOption::getId).toList();
        Map<Long, List<MasterProductOptionItem>> itemsByOption = optionIds.isEmpty() ? Map.of()
                : masterProductOptionItemRepository.findByOptionIdIn(optionIds).stream()
                        .collect(Collectors.groupingBy(it -> it.getOption().getId()));
        List<KnownOption> known = new ArrayList<>();
        for (MasterProductOption option : options) {
            Map<Long, Integer> vector = new LinkedHashMap<>();
            for (MasterProductOptionItem item : itemsByOption.getOrDefault(option.getId(), List.of())) {
                vector.put(item.getProduct().getId(), item.getQuantity());
            }
            known.add(new KnownOption(option, vector));
        }
        return known;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * D14 판정 = <b>쿠팡의 실제 카테고리가 우리가 앞으로 보낼 카테고리와 같은가</b>. 역조회가 실패한 경우도,
     * 역조회는 됐지만 다른 카테고리인 경우도 똑같이 {@code false} 다(사용자가 볼 결과가 같다).
     *
     * <p>어느 쪽이든 막지 않는다 — 등록·수정 payload 는 원래도 마스터 카테고리로 만들어지므로 폴백이 곧
     * 현행 동작이다.</p>
     */
    private boolean categoryMatches(MasterProduct master, Platform platform, String marketCategoryCode) {
        if (marketCategoryCode == null || marketCategoryCode.isBlank()) {
            return false;
        }
        return platformCategoryRepository.findByPlatformAndCode(platform, marketCategoryCode)
                .flatMap(pc -> categoryMappingRepository.findByPlatformCategoryId(pc.getId()))
                .map(cm -> cm.getCategory().getId().equals(master.getCategory().getId()))
                .orElse(false);
    }

    /**
     * D17: 채널 태그 = 마켓 태그 − 마스터 태그. {@code TagMergeService} 가 채널 → 마스터 순으로 합치므로,
     * 합친 결과가 다시 마켓의 원본 집합과 같아진다(순서는 보존되지 않는다 — 수용).
     */
    private List<String> channelTags(MasterProduct master, List<String> marketTags) {
        Set<String> masterTags = master.getTags() == null ? Set.of() : new HashSet<>(master.getTags());
        List<String> channelTags = new ArrayList<>();
        for (String tag : marketTags) {
            if (!masterTags.contains(tag) && !channelTags.contains(tag)) {
                channelTags.add(tag);
            }
        }
        return channelTags;
    }

    /**
     * 미리보기 구성 줄. ⚠️ {@code component.getProduct()} 는 LAZY 프록시라 브랜드·이름을 직접 읽으면 줄마다
     * 쿼리가 나간다 → id 만 뽑아 한 번에 로드한다({@code MasterProductServiceImpl} 과 같은 함정).
     */
    private List<ListingImportPreviewResponse.Component> previewComponents(List<MasterProductComponent> components) {
        List<Long> productIds = components.stream().map(c -> c.getProduct().getId()).toList();
        Map<Long, Product> productsById = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        List<ListingImportPreviewResponse.Component> rows = new ArrayList<>();
        for (Long productId : productIds) {
            Product product = productsById.get(productId);
            rows.add(ListingImportPreviewResponse.Component.builder()
                    .productId(productId)
                    .brand(product == null ? null : product.getBrand())
                    .productName(product == null ? null : product.getProductName())
                    .build());
        }
        return rows;
    }

    /** 미리보기·커밋이 공유하는 검증 결과. */
    private record ImportContext(MasterProduct master, Seller seller, MarketplaceAccount account,
                                 List<MasterProductComponent> components) {
    }

    /** 마스터 옵션 + 그 BOM 벡터(D10 비교 대상). 이번 요청에서 새로 만든 옵션도 여기에 쌓인다. */
    private record KnownOption(MasterProductOption option, Map<Long, Integer> vector) {
    }
}
