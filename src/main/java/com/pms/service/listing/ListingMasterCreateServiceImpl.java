package com.pms.service.listing;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.dto.request.ListingMasterCreateRequest;
import com.pms.dto.request.MasterCategoryRequest;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.response.ListingMasterCreateResponse;
import com.pms.dto.response.ListingMasterPreviewResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.exception.ValidationException;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.service.MasterProductService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 판매상품으로 마스터 프로덕트 생성 + 연결(FEATURE_2609_22 / 04). See {@link ListingMasterCreateService}.
 *
 * <p>구조는 {@link CoupangListingImportServiceImpl}(거울쌍)을 그대로 따른다: 검증 → 쿠팡 1회 읽기 → 대조,
 * 그리고 커밋은 같은 검증을 처음부터 다시 돈다. 다른 점은 <b>방향</b>뿐이다 — 저쪽은 마스터에서 셀로 값을
 * 복사하고, 이쪽은 셀에서 마스터로 옮긴다.</p>
 *
 * <p>⚠️ 이 서비스는 {@code ListingAssetService} 에 <b>의존하지 않는다</b>(D31 얕은 생성). 썸네일·상세가 생기는
 * 순간 이 셀은 layer A/B 의 대상이 되고 [마켓 반영] 한 번에 실제 판매중 상품이 우리 상세로 덮인다. 의존성을
 * 아예 두지 않는 것이 그 사고를 막는 장치다 — "친절하게" 주입하지 말 것.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingMasterCreateServiceImpl implements ListingMasterCreateService {

    private static final Logger log = LoggerFactory.getLogger(ListingMasterCreateServiceImpl.class);

    /**
     * {@code MasterOptionRequest.@Max(99999)} 는 컨트롤러 {@code @Valid} 경계에서만 걸린다. 이 경로는 서비스에서
     * 직접 {@code createMasterProduct} 를 부르므로 <b>검증 없이 저장된다</b> → 여기서 같은 상한을 다시 막는다.
     */
    private static final int MAX_STOCK_QUANTITY = 99999;

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final ProductRepository productRepository;
    private final MasterProductRepository masterProductRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final PlatformCategoryRepository platformCategoryRepository;
    private final CategoryMappingRepository categoryMappingRepository;
    private final ListingChannelResolver resolver;
    // 마스터 생성/카테고리 지정은 반드시 이 경로로 한다 — 엔티티를 직접 save 하면 옵션 최소 1개, 옵션명 중복,
    // 구성 커버리지, leaf/쿠팡 매핑 가드를 전부 우회한다.
    private final MasterProductService masterProductService;

    // ------------------------------------------------------------------ preview

    @Override
    public ListingMasterPreviewResponse preview(Long listingId) {
        CellContext ctx = validate(listingId);

        List<ListingMasterPreviewResponse.OptionDiff> diffs = new ArrayList<>();
        for (ProductListingOption option : ctx.cellOptions()) {
            ImportedProduct.Option market = ctx.pairs().get(option.getId());
            diffs.add(ListingMasterPreviewResponse.OptionDiff.builder()
                    .optionName(option.getOptionName())
                    .coupangItemName(market.itemName())
                    .currentOptionId(option.getPlatformOptionId())
                    .coupangVendorItemId(market.vendorItemId())
                    // D27: 커밋이 쿠팡 값으로 덮어쓴다. 미리보기는 그 사실을 알려줄 뿐이다.
                    .optionIdMismatch(!Objects.equals(option.getPlatformOptionId(), market.vendorItemId()))
                    .currentPrice(option.getSellingPrice())
                    .coupangPrice(market.salePrice())
                    .priceMismatch(priceDiffers(option.getSellingPrice(), market.salePrice()))
                    .build());
        }

        Long suggestedCategoryId = null;
        String suggestedCategoryName = null;
        // 2609_22/D26: 마스터가 아직 없으니 폴백할 카테고리도 없다. 역조회 성공 = 프리필, 실패 = null
        // (프론트가 사용자에게 표준 카테고리를 고르게 한다). D15 경고 문구는 이 경로에서 쓰지 않는다.
        var mapping = ctx.fetched().categoryCode() == null || ctx.fetched().categoryCode().isBlank()
                ? java.util.Optional.<com.pms.domain.CategoryMapping>empty()
                : platformCategoryRepository
                        .findByPlatformAndCode(ctx.cell().getPlatform(), ctx.fetched().categoryCode())
                        .flatMap(pc -> categoryMappingRepository.findByPlatformCategoryId(pc.getId()));
        if (mapping.isPresent()) {
            suggestedCategoryId = mapping.get().getCategory().getId();
            suggestedCategoryName = mapping.get().getCategory().getName();
        }

        return ListingMasterPreviewResponse.builder()
                .listingName(ctx.cell().getName())
                .coupangProductName(ctx.fetched().productName())
                .suggestedMasterName(suggestedMasterName(ctx))
                // 어댑터가 이미 mapStatus 로 변환해 준 값이다 — 여기서 다시 변환하지 않는다.
                .status(ctx.fetched().status())
                .categoryCode(ctx.fetched().categoryCode())
                .suggestedCategoryId(suggestedCategoryId)
                .suggestedCategoryName(suggestedCategoryName)
                .components(previewComponents(ctx.componentIds()))
                .options(diffs)
                .coupangOnlyOptions(ctx.coupangOnlyOptions())
                .build();
    }

    // ------------------------------------------------------------------ create

    @Override
    @Transactional
    public ListingMasterCreateResponse create(Long listingId, ListingMasterCreateRequest request) {
        // Defence in depth: 미리보기를 건너뛴 직접 호출도 같은 순서로 전부 다시 검증한다(쿠팡도 다시 읽는다).
        CellContext ctx = validate(listingId);

        // --- 1) 마스터 생성. 엔티티 직접 save 가 아니라 마스터 경로를 그대로 탄다(옵션 최소 1개·이름 중복·
        //        구성 커버리지 가드가 여기 붙어 있다). fieldValues/tags/sourceImageUrl/카테고리 메타는
        //        넘기지 않는다 — D31 얕은 생성.
        List<MasterOptionRequest> optionRequests = new ArrayList<>();
        for (ProductListingOption option : ctx.cellOptions()) {
            List<MasterOptionRequest.OptionItem> items = ctx.bom(option).stream()
                    .map(line -> MasterOptionRequest.OptionItem.builder()
                            .productId(line.getProduct().getId())
                            .quantity(line.getQuantity())
                            .build())
                    .toList();
            optionRequests.add(MasterOptionRequest.builder()
                    .name(option.getOptionName())
                    .items(items)
                    .stockQuantity(option.getStockQuantity())
                    .build());
        }
        Long masterId = masterProductService.createMasterProduct(MasterProductRequest.builder()
                .name(request.getMasterName())
                .componentProductIds(new ArrayList<>(ctx.componentIds()))
                // 부록 A: 구 데이터라 셀에 배송/박스가 없으면 null 로 만든다 — 자산을 만들지 않으므로
                // 지금 막을 이유가 없다(마스터에서 나중에 지정한다).
                .defaultDeliveryId(ctx.cell().getDelivery() == null ? null : ctx.cell().getDelivery().getId())
                .defaultPackageId(ctx.cell().getPackage_() == null ? null : ctx.cell().getPackage_().getId())
                .options(optionRequests)
                .build()).getId();

        // --- 2) 카테고리 지정. leaf 여부·쿠팡 매핑 존재 가드는 setCategory 의 기존 규칙을 그대로 탄다(D26).
        masterProductService.setCategory(masterId, MasterCategoryRequest.builder()
                .categoryId(request.getCategoryId()).build());

        MasterProduct master = masterProductRepository.findScopedById(masterId)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", masterId));

        // --- 3) 셀 링크. 🔴 1 → 3 순서를 뒤집지 말 것: 1)이 도는 동안 이 셀은 아직 마스터에 붙어 있지 않아
        //        옵션 전파 경로 자체가 성립하지 않는다. 지금의 createMasterProduct 는 onOptionCreated 를
        //        부르지 않지만 그 사실에 기대지 않는다 — 나중에 배치 생성이 createOption(훅 호출) 쪽으로
        //        통합되면, 셀을 먼저 붙인 코드는 그날 조용히 옵션을 중복 생성한다.
        ProductListing linked = productListingRepository.save(ctx.cell().toBuilder()
                .masterProduct(master)
                .status(ctx.fetched().status())
                // D16: 쿠팡의 실제 leaf 코드 — 표시·대조용이지 payload 용이 아니다.
                .platformCategoryCode(ctx.fetched().categoryCode())
                .needsMarketSync(false)
                // D33: category/delivery/package 컬럼은 그대로 둔다(조회 화면이 표시한다).
                .build());

        // --- 4) 셀 옵션 링크. 마스터 옵션은 엔티티가 필요하므로(FK) 다시 읽는다 — MasterProductResponse 의
        //        options 로는 FK 를 걸 수 없다. 매칭 축은 이름이고, 셀 옵션명을 그대로 넣었으며 옵션명 중복은
        //        검증에서 이미 막혔다(매칭도 trim 기준).
        Map<String, MasterProductOption> masterOptionsByName = new HashMap<>();
        for (MasterProductOption masterOption : masterProductOptionRepository.findByMasterProductId(masterId)) {
            masterOptionsByName.put(trimmed(masterOption.getName()), masterOption);
        }
        for (ProductListingOption option : ctx.cellOptions()) {
            MasterProductOption masterOption = masterOptionsByName.get(trimmed(option.getOptionName()));
            if (masterOption == null) {
                // 도달 불가(1 이 셀 옵션명 그대로 만들었다). 조용히 null FK 를 남기면 채널 전용 옵션(D2)이
                // 되어 전파가 영영 이 옵션을 건너뛰므로, 트랜잭션을 되돌린다.
                throw new IllegalStateException("마스터 옵션 매칭 실패: " + option.getOptionName());
            }
            ImportedProduct.Option market = ctx.pairs().get(option.getId());
            productListingOptionRepository.save(option.toBuilder()
                    .productListing(linked)
                    .masterProductOption(masterOption)                      // FK, 01/D1
                    // D12: 마켓에 이 이름으로 올라가 있다 — 마스터 rename 이 덮으면 안 된다.
                    .optionNameSource(GeneratedContentSource.MANUAL_OVERRIDE)
                    // D13: 재생성이 계산가로 덮지 못하게 한다.
                    .priceSource(GeneratedContentSource.MANUAL_OVERRIDE)
                    // D27 자동 교정: 셀의 옵션 id 가 비었거나 틀렸으면 쿠팡 값으로 덮어쓴다(구매목록 매핑이
                    // 없던 셀이 여기서 살아난다 — 이 기능의 핵심 이득).
                    .platformOptionId(market.vendorItemId())
                    .sellerProductItemId(market.sellerProductItemId())
                    // D20: id 가 있다 ⇒ 쿠팡이 승인했다.
                    .approvalStatus(market.vendorItemId() != null
                            ? OptionApprovalStatus.APPROVED : OptionApprovalStatus.NOT_APPROVED)
                    // ⚠️ sellingPrice/originalPrice/optionName/active/stockQuantity 는 무변경 — 셀의 현재값이
                    // 운영값이다. 쿠팡 가격을 셀에 반영하지 말 것(가격 동기화 기능이 아니다, 2609_19 경로).
                    .build());
        }

        // 5) regenerateAssets 를 호출하지 않는다(D31). 이 서비스에는 그 의존성 자체가 없다.
        return ListingMasterCreateResponse.builder()
                .masterProductId(masterId)
                .productListingId(linked.getId())
                .optionCount(ctx.cellOptions().size())
                .build();
    }

    // ------------------------------------------------------------------ validation

    /**
     * 미리보기·커밋 공통 검증(Step 1 의 1~10). <b>순서가 규칙이다</b> — 사용자가 확정 화면까지 갔다가 실패하면
     * 안 되므로 커밋도 같은 순서로 다시 돈다. 400 = {@link ValidationException}(마스터 경로의 관례),
     * 404 = {@link ResourceNotFoundException}.
     */
    private CellContext validate(Long listingId) {
        // 1. 셀
        ProductListing cell = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));
        // 2. 이미 연결됨
        if (cell.getMasterProduct() != null) {
            throw new ValidationException("이미 마스터에 연결된 판매상품입니다");
        }
        // 3. 마켓 상품 ID (D29: 없으면 쿠팡을 조회할 수 없다 — legacy 수정 화면에서 채워야 한다)
        if (cell.getPlatformProductId() == null || cell.getPlatformProductId().isBlank()) {
            throw new ValidationException("마켓 상품 ID가 없는 판매상품입니다");
        }

        // 4. 옵션
        List<ProductListingOption> cellOptions = productListingOptionRepository.findByProductListingId(listingId);
        if (cellOptions.isEmpty()) {
            throw new ValidationException("옵션이 없는 판매상품입니다");
        }
        Set<String> names = new HashSet<>();
        for (ProductListingOption option : cellOptions) {
            // trim 기준으로 본다 — createMasterProduct 가 trim 기준으로 판정하므로(:515), raw 로 보면
            // "6입"/"6입 " 이 여기를 통과했다가 나중에 영문 아닌 다른 문구로 터진다.
            if (!names.add(trimmed(option.getOptionName()))) {
                throw new ValidationException("옵션명이 중복되어 마스터로 만들 수 없습니다: " + option.getOptionName());
            }
        }

        // 5·6. 셀 BOM — 옵션마다 물품 집합이 같아야 하고(D35), 라인 자체도 성해야 한다.
        Map<Long, List<ProductListingProduct>> bomByOption = productListingProductRepository
                .findByProductListingOptionIdIn(cellOptions.stream().map(ProductListingOption::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(line -> line.getProductListingOption().getId()));

        Set<Long> componentIds = null;
        for (ProductListingOption option : cellOptions) {
            List<ProductListingProduct> lines = bomByOption.getOrDefault(option.getId(), List.of());
            if (lines.isEmpty()) {
                throw new ValidationException("구성품이 없는 옵션: " + option.getOptionName());
            }
            Set<Long> productIds = lines.stream().map(line -> line.getProduct().getId())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (componentIds == null) {
                componentIds = productIds;
            } else if (!componentIds.equals(productIds)) {
                // assertCoversComponents 가 마스터 생성 도중에 터지는 대신, 여기서 사람이 읽을 수 있는
                // 문구로 막는다(D35).
                throw new ValidationException(
                        "옵션마다 구성 물품이 달라 마스터로 만들 수 없습니다 — 판매상품을 먼저 정리하세요");
            }
            if (option.getStockQuantity() != null && option.getStockQuantity() > MAX_STOCK_QUANTITY) {
                throw new ValidationException("재고 수량이 " + MAX_STOCK_QUANTITY + "를 넘는 옵션: "
                        + option.getOptionName());
            }
            // 🔴 product_listing_product 에는 (옵션, 물품) 유니크 제약이 없다. MasterProductServiceImpl.toVector
            // 는 중복을 (first, dup) -> dup 로 조용히 덮어써 예외 없이 "수량이 틀린 마스터"를 만든다.
            // 합산하지 않는다(어느 쪽이 옳은지 알 수 없다) — 중단한다.
            if (productIds.size() != lines.size()) {
                throw new ValidationException("같은 물품이 두 번 들어간 옵션: " + option.getOptionName());
            }
            for (ProductListingProduct line : lines) {
                if (line.getQuantity() == null || line.getQuantity() < 1) {
                    throw new ValidationException("수량이 없는 구성품이 있는 옵션: " + option.getOptionName());
                }
            }
        }

        // 7. 어댑터 (미지원 플랫폼 400)
        ListingChannel adapter = resolver.resolve(cell.getPlatform());
        // 8. 계정 (없음 404 / 비활성 400) — ListingRegistrationServiceImpl.resolveAccount 와 같은 규칙
        MarketplaceAccount account = resolveAccount(cell);

        // 9. 쿠팡 조회 (D29)
        ImportedProduct fetched;
        try {
            fetched = adapter.fetchProduct(cell.getPlatformProductId(), account);
        } catch (RuntimeException e) {
            log.warn("쿠팡 상품 조회 실패 listingId={} platformProductId={}",
                    listingId, cell.getPlatformProductId(), e);
            throw new ValidationException("쿠팡에서 상품을 찾을 수 없습니다 — 상품 ID를 확인하세요");
        }
        if (fetched.options().isEmpty()) {
            throw new ValidationException("쿠팡에서 상품을 찾을 수 없습니다 — 상품 ID를 확인하세요");
        }

        // 10. 셀 옵션 ↔ 쿠팡 옵션 매칭 (D28)
        Map<Long, ImportedProduct.Option> pairs = matchOptions(cellOptions, fetched.options());
        Set<ImportedProduct.Option> matched = new HashSet<>(pairs.values());
        // D30: 쿠팡에만 있는 옵션은 경고만 한다 — BOM 이 없어 원가·마진이 비고 구매목록도 안 되므로
        // 가져오지 않는다.
        List<String> coupangOnly = fetched.options().stream()
                .filter(option -> !matched.contains(option))
                .map(ImportedProduct.Option::itemName)
                .toList();

        return new CellContext(cell, cellOptions, bomByOption, componentIds, account, fetched, pairs, coupangOnly);
    }

    /** {@code ListingRegistrationServiceImpl.resolveAccount} 와 같은 규칙(셀이 있으므로 그대로 재사용). */
    private MarketplaceAccount resolveAccount(ProductListing cell) {
        MarketplaceAccount account = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", cell.getSeller().getId()));
        if (Boolean.FALSE.equals(account.getIsActive())) {
            throw new ValidationException("비활성 계정");
        }
        return account;
    }

    /**
     * 매칭 축(D27·D21 과 같은 원칙): 1순위 {@code platformOptionId == vendorItemId}, 못 찾으면
     * {@code optionName == itemName} 폴백.
     *
     * <p>⚠️ 폴백이 "id 가 없을 때"만이 아니라 "id 로 못 찾았을 때"도 도는 것이 핵심이다 — 셀의 옵션 id 가
     * <b>틀린</b> 경우(D27 자동 교정 대상)가 정확히 그 경우다. 쿠팡 itemName 이 중복이면 400: 임의로 첫 번째에
     * 붙이면 구성이 통째로 뒤바뀐다.</p>
     */
    private Map<Long, ImportedProduct.Option> matchOptions(List<ProductListingOption> cellOptions,
                                                           List<ImportedProduct.Option> marketOptions) {
        Map<String, ImportedProduct.Option> byVendorItemId = new HashMap<>();
        Map<String, ImportedProduct.Option> byName = new HashMap<>();
        Set<String> ambiguousNames = new HashSet<>();
        for (ImportedProduct.Option option : marketOptions) {
            if (option.vendorItemId() != null) {
                byVendorItemId.put(option.vendorItemId(), option);
            }
            if (option.itemName() != null && byName.put(trimmed(option.itemName()), option) != null) {
                ambiguousNames.add(trimmed(option.itemName()));
            }
        }

        Map<Long, ImportedProduct.Option> pairs = new LinkedHashMap<>();
        Set<ImportedProduct.Option> used = new HashSet<>();
        for (ProductListingOption cellOption : cellOptions) {
            ImportedProduct.Option market = cellOption.getPlatformOptionId() == null
                    ? null : byVendorItemId.get(cellOption.getPlatformOptionId());
            if (market == null) {
                String name = trimmed(cellOption.getOptionName());
                if (ambiguousNames.contains(name)) {
                    throw new ValidationException("쿠팡 옵션명이 중복되어 식별할 수 없습니다: " + name);
                }
                market = byName.get(name);
            }
            if (market == null) {
                // D28: 정상 데이터라면 있을 수 없다(쿠팡 옵션이 셀보다 적다).
                throw new ValidationException("쿠팡에 없는 옵션이 있습니다: " + cellOption.getOptionName()
                        + " — 판매상품을 먼저 정리하세요");
            }
            if (!used.add(market)) {
                throw new ValidationException("쿠팡 옵션이 중복 매칭되었습니다: " + cellOption.getOptionName()
                        + " — 판매상품을 먼저 정리하세요");
            }
            pairs.put(cellOption.getId(), market);
        }
        return pairs;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * D25 마스터명 기본값 = 첫 옵션 첫 구성품의 {@code label(brand, name)}.
     *
     * <p>순서를 못박는다: 옵션 = {@code optionName} 오름차순 첫 번째, 구성품 = 그 옵션 BOM 의
     * {@code productId} 오름차순 첫 번째. {@code RegistrationNameGenerator} 가 쓰는 정렬과 같다 — 같은 셀은
     * 몇 번을 눌러도 같은 이름을 제안해야 한다.</p>
     */
    private String suggestedMasterName(CellContext ctx) {
        ProductListingOption first = ctx.cellOptions().stream()
                .min(Comparator.comparing(option -> trimmed(option.getOptionName())))
                .orElseThrow();
        Long productId = ctx.bom(first).stream()
                .map(line -> line.getProduct().getId())
                .min(Comparator.naturalOrder())
                .orElseThrow();
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            return ctx.cell().getName();
        }
        return label(product.getBrand(), product.getProductName());
    }

    /**
     * {@code RegistrationNameGenerator.label} 의 규칙(brand 가 공백이면 이름만). ⚠️ 그쪽은 private 이다 —
     * 가시성을 바꾸지 말고 규칙만 복제한다.
     */
    private String label(String brand, String name) {
        return (brand == null || brand.isBlank()) ? name : brand + " " + name;
    }

    /**
     * 미리보기 구성 줄. ⚠️ BOM 의 {@code getProduct()} 는 LAZY 프록시라 브랜드·이름을 직접 읽으면 줄마다 쿼리가
     * 나간다 → id 만 뽑아 한 번에 로드한다(02 와 같은 함정).
     */
    private List<ListingMasterPreviewResponse.Component> previewComponents(Set<Long> componentIds) {
        Map<Long, Product> productsById = productRepository.findAllById(componentIds).stream()
                .collect(Collectors.toMap(Product::getId, product -> product));
        List<ListingMasterPreviewResponse.Component> rows = new ArrayList<>();
        for (Long productId : componentIds) {
            Product product = productsById.get(productId);
            rows.add(ListingMasterPreviewResponse.Component.builder()
                    .productId(productId)
                    .brand(product == null ? null : product.getBrand())
                    .productName(product == null ? null : product.getProductName())
                    .build());
        }
        return rows;
    }

    /** 스케일이 달라도 같은 값이면 차이가 아니다(10900 vs 10900.00). */
    private boolean priceDiffers(BigDecimal cellPrice, BigDecimal marketPrice) {
        if (cellPrice == null || marketPrice == null) {
            return cellPrice != marketPrice;
        }
        return cellPrice.compareTo(marketPrice) != 0;
    }

    private String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    /**
     * 미리보기·커밋이 공유하는 검증 결과.
     *
     * @param cell              마스터 미연결 셀
     * @param cellOptions       셀 옵션(저장 순서 그대로)
     * @param bomByOption       셀 옵션 id → BOM 라인
     * @param componentIds      전 옵션 공통 물품 집합(D35 로 동일함이 보장된 뒤의 값)
     * @param account           마켓 계정
     * @param fetched           쿠팡 원본
     * @param pairs             셀 옵션 id → 쿠팡 옵션
     * @param coupangOnlyOptions 쿠팡에만 있는 옵션명(D30, 경고용)
     */
    private record CellContext(ProductListing cell,
                               List<ProductListingOption> cellOptions,
                               Map<Long, List<ProductListingProduct>> bomByOption,
                               Set<Long> componentIds,
                               MarketplaceAccount account,
                               ImportedProduct fetched,
                               Map<Long, ImportedProduct.Option> pairs,
                               List<String> coupangOnlyOptions) {

        List<ProductListingProduct> bom(ProductListingOption option) {
            return bomByOption.getOrDefault(option.getId(), List.of());
        }
    }
}
