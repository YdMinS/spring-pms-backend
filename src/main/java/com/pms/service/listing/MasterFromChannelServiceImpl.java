package com.pms.service.listing;

import com.pms.domain.CategoryMapping;
import com.pms.domain.GeneratedContentSource;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Platform;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.Seller;
import com.pms.dto.request.MasterCategoryRequest;
import com.pms.dto.request.MasterFromChannelPreviewRequest;
import com.pms.dto.request.MasterFromChannelRequest;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.response.ListingMasterCreateResponse;
import com.pms.dto.response.MasterFromChannelPreviewResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.SellerRepository;
import com.pms.service.CategoryMetaService;
import com.pms.service.MasterProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 마켓 상품으로 마스터 만들기(FEATURE_2609_45 / 01). See {@link MasterFromChannelService}.
 *
 * <p>구조는 {@link CoupangListingImportServiceImpl}(검증 → 마켓 1회 읽기 → 옵션 확정 → 셀 → 셀 옵션·BOM)과
 * {@link ListingMasterCreateServiceImpl}(마스터는 {@code masterProductService} 경로로만 만든다)의 합이다.
 * 커밋도 같은 검증을 처음부터 다시 돈다(미리보기를 건너뛴 직접 호출 방어).</p>
 *
 * <p>⚠️ 이 서비스는 {@code ListingAssetService} 에 <b>의존하지 않는다</b>(D5 얕은 생성) — 인터페이스 주석의
 * 이유 그대로다. 의존성이 없는 것이 그 장치다.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MasterFromChannelServiceImpl implements MasterFromChannelService {

    /**
     * {@link ListingChannel#fetchProduct} 를 실제로 구현한 플랫폼. ⚠️ 어댑터 기본 구현의
     * {@code UnsupportedOperationException} 은 전역 핸들러가 없어 500 이 되므로 여기서 400 으로 막는다.
     * 🔴 네이버가 붙는 날 바뀌는 것은 이 한 줄이어야 한다.
     */
    private static final Set<Platform> SUPPORTED_PLATFORMS = Set.of(Platform.COUPANG);

    private final SellerRepository sellerRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final ProductRepository productRepository;
    private final MasterProductRepository masterProductRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final PlatformCategoryRepository platformCategoryRepository;
    private final CategoryMappingRepository categoryMappingRepository;
    // 🔴 D17-1: 어댑터는 여기서 얻는다. CoupangListingAdapter 를 직접 주입하면 seam 이 무의미해진다.
    private final ListingChannelResolver resolver;
    // 마스터 생성/카테고리 지정은 반드시 이 경로로 한다 — 엔티티를 직접 save 하면 옵션 최소 1개, 옵션명 중복,
    // 구성 커버리지, leaf/쿠팡 매핑 가드를 전부 우회한다.
    private final MasterProductService masterProductService;
    private final CategoryMetaService categoryMetaService;

    // ------------------------------------------------------------------ preview

    @Override
    public MasterFromChannelPreviewResponse preview(MasterFromChannelPreviewRequest request) {
        Platform platform = Platform.from(request.getPlatform());
        ChannelContext ctx = validate(platform, request.getSellerId(), request.getPlatformProductId());
        ImportedProduct fetched = fetchProduct(ctx, request.getPlatformProductId());

        Map<String, String> common = commonAttributes(fetched.options());
        Optional<CategoryMapping> mapping = reverseLookup(platform, fetched.categoryCode());

        String productName = fetched.productName();
        return MasterFromChannelPreviewResponse.builder()
                .productName(productName)
                // 비었으면 null — 프론트가 마스터 이름 입력을 강제한다.
                .suggestedMasterName(productName == null || productName.isBlank() ? null : productName)
                // 어댑터가 이미 mapStatus 로 변환해 준 값이다 — 여기서 다시 변환하지 않는다.
                .status(fetched.status())
                .categoryCode(fetched.categoryCode())
                .suggestedCategoryId(mapping.map(m -> m.getCategory().getId()).orElse(null))
                .suggestedCategoryName(mapping.map(m -> m.getCategory().getName()).orElse(null))
                .categoryResolved(mapping.isPresent())
                .options(fetched.options().stream()
                        .map(option -> MasterFromChannelPreviewResponse.Option.builder()
                                .itemName(option.itemName())
                                .platformOptionId(option.vendorItemId())
                                .sellerProductItemId(option.sellerProductItemId())
                                .salePrice(option.salePrice())
                                .stockQuantity(option.stockQuantity())
                                .attributes(differingAttributes(option, common))
                                .build())
                        .collect(Collectors.toList()))
                .commonAttributes(common)
                .notices(productNotices(fetched.options()))
                .noticeGroup(fetched.noticeGroup())
                .build();
    }

    // ------------------------------------------------------------------ create

    @Override
    @Transactional
    public ListingMasterCreateResponse create(MasterFromChannelRequest request) {
        // Defence in depth: 미리보기를 건너뛴 직접 호출도 같은 순서로 전부 다시 검증한다.
        Platform platform = Platform.from(request.getPlatform());
        ChannelContext ctx = validate(platform, request.getSellerId(), request.getPlatformProductId());
        // The market is re-read here on purpose: prices/options may have moved since the preview, and the
        // client's copy of them is never trusted.
        ImportedProduct fetched = fetchProduct(ctx, request.getPlatformProductId());

        Map<MasterFromChannelRequest.OptionSpec, ImportedProduct.Option> pairs =
                matchOptions(request.getOptions(), fetched.options());
        Map<Long, Product> componentProducts = requireProducts(request.getComponentProductIds());
        for (MasterFromChannelRequest.OptionSpec spec : request.getOptions()) {
            assertCoversComponents(spec, componentProducts);
        }
        Map<String, String> common = commonAttributes(fetched.options());

        // --- 1) 마스터 + 옵션 원자 생성. 엔티티 직접 save 가 아니라 마스터 경로를 그대로 탄다.
        List<MasterOptionRequest> optionRequests = new ArrayList<>();
        for (MasterFromChannelRequest.OptionSpec spec : request.getOptions()) {
            ImportedProduct.Option market = pairs.get(spec);
            Map<String, String> differing = differingAttributes(market, common);
            optionRequests.add(MasterOptionRequest.builder()
                    .name(market.itemName())                       // 마스터 옵션명 = 마켓 itemName
                    .items(spec.getComponents().stream()
                            .map(component -> MasterOptionRequest.OptionItem.builder()
                                    .productId(component.getProductId())
                                    .quantity(component.getQuantity())
                                    .build())
                            .toList())
                    // 🔴 D4-1: 옵션마다 다른 속성(수량·중량 등)은 마스터 옵션에 실린다. items[0] 의 속성을 전
                    //    옵션에 공통 적용하면 6개입 옵션의 수량이 12개입 옵션으로 나간다.
                    .categoryAttributes(differing.isEmpty() ? null : differing)
                    // 🔴 D3-1: stockQuantity 를 넣지 않는다. 마스터 옵션의 재고는 채널 재고의 상한이라
                    //    (ListingStockPolicy.ceiling) 한 채널의 쿠팡 재고를 박으면 나중에 붙는 모든 채널이
                    //    그 값에 갇힌다. 쿠팡 재고는 셀 옵션에만 넣는다(아래 4).
                    .build());
        }
        // ⚠️ defaultDeliveryId/defaultPackageId 는 null — 쿠팡에 우리 택배·박스 개념이 없다. 판매가 계산은
        //    마스터 상세에서 배송·박스를 지정한 뒤에야 돈다(D5 얕은 생성과 같은 성격).
        Long masterId = masterProductService.createMasterProduct(MasterProductRequest.builder()
                .name(request.getMasterName())
                .componentProductIds(new ArrayList<>(componentProducts.keySet()))
                .options(optionRequests)
                .build()).getId();

        // --- 2) 카테고리 지정. leaf 여부·쿠팡 매핑 존재 가드는 setCategory 의 기존 규칙을 그대로 탄다.
        masterProductService.setCategory(masterId, MasterCategoryRequest.builder()
                .categoryId(request.getCategoryId()).build());

        // --- 3) 마스터 속성·고시 (D4·D4-1·D4-2). ⚠️ 전부 비면 호출하지 않는다(빈 값으로 덮어쓰지 말 것).
        Map<String, String> notices = productNotices(fetched.options());
        if (!common.isEmpty() || !notices.isEmpty()) {
            categoryMetaService.updateCategoryAttributes(masterId,
                    common.isEmpty() ? null : common,
                    notices.isEmpty() ? null : notices,
                    fetched.noticeGroup());
        }

        MasterProduct master = masterProductRepository.findScopedById(masterId)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", masterId));

        // --- 4) 셀
        String marketName = fetched.productName();
        ProductListing cell = productListingRepository.save(ProductListing.builder()
                .masterProduct(master)
                .seller(ctx.seller())
                .platform(platform)
                .platformProductId(request.getPlatformProductId())
                .platformCategoryCode(fetched.categoryCode())
                // The market name is display data; a missing one would violate NOT NULL, so fall back.
                .name(marketName == null || marketName.isBlank() ? request.getMasterName() : marketName)
                // The product is already live on the market — take its status, and it is not pending sync.
                .status(fetched.status())
                .needsMarketSync(false)
                // ⚠️ 2609_22/D17(쿠팡태그 − 마스터태그 차집합)은 여기 해당 없다 — 신규 마스터는 태그가 비어
                //    있어 차집합이 항상 원본과 같다. null(빈 리스트 아님) = 채널 태그 없음.
                .tags(fetched.tags().isEmpty() ? null : new ArrayList<>(fetched.tags()))
                .build());

        // --- 5) 셀 옵션 + 6) 셀 BOM
        // 마스터 옵션은 엔티티가 필요하므로(FK) 다시 읽는다 — createMasterProduct 는 MasterProductResponse 를
        // 돌려주어 FK 를 걸 엔티티가 없다. 매칭 축은 이름이고, 1)이 마켓 itemName 그대로 만들었으며 옵션명
        // 중복은 fetchProduct 가 앞에서 막았다(D7). ⚠️ 2609_22/D1 의 "매칭 축은 옵션 id, 이름이 아니다" 에
        // 대한 예외가 아니라 FK 를 거는 순간의 사정이다 — 저장된 뒤의 매칭 축은 언제나 FK 다.
        Map<String, MasterProductOption> masterOptionsByName = new HashMap<>();
        for (MasterProductOption masterOption : masterProductOptionRepository.findByMasterProductId(masterId)) {
            masterOptionsByName.put(trimmed(masterOption.getName()), masterOption);
        }
        for (MasterFromChannelRequest.OptionSpec spec : request.getOptions()) {
            ImportedProduct.Option market = pairs.get(spec);
            MasterProductOption masterOption = masterOptionsByName.get(trimmed(market.itemName()));
            if (masterOption == null) {
                // 도달 불가(1 이 마켓 itemName 그대로 만들었다). 조용히 null FK 를 남기면 채널 전용 옵션이
                // 되어 전파가 영영 이 옵션을 건너뛰므로, 트랜잭션을 되돌린다.
                throw new IllegalStateException("마스터 옵션 매칭 실패: " + market.itemName());
            }
            ProductListingOption listingOption = productListingOptionRepository.save(ProductListingOption.builder()
                    .productListing(cell)
                    .masterProductOption(masterOption)                     // FK
                    // 마켓에 이 이름으로 올라가 있다 — 마스터 rename 이 덮으면 안 된다.
                    .optionName(market.itemName())
                    .optionNameSource(GeneratedContentSource.MANUAL_OVERRIDE)
                    // 마켓의 실판매가. MANUAL_OVERRIDE 라야 재생성이 계산가로 덮지 않는다.
                    .sellingPrice(market.salePrice())
                    .originalPrice(market.originalPrice())
                    .priceSource(GeneratedContentSource.MANUAL_OVERRIDE)
                    // 마켓에서 읽어온 값이라 두 컬럼이 같은 값으로 출발한다("아직 안 밀림"으로 보이지 않게).
                    .marketPrice(market.salePrice())
                    .marketPriceAt(LocalDateTime.now())
                    .stockQuantity(market.stockQuantity())
                    .platformOptionId(market.vendorItemId())
                    .sellerProductItemId(market.sellerProductItemId())
                    // id 가 있다 ⇒ 쿠팡이 승인했다.
                    .approvalStatus(market.vendorItemId() != null
                            ? OptionApprovalStatus.APPROVED : OptionApprovalStatus.NOT_APPROVED)
                    .active(true)                                          // it is being sold on the market
                    // ⚠️ categoryAttributes/categoryNotices 를 넣지 않는다 — 마스터 카테고리 = 마켓 카테고리라
                    //    마스터 값이 곧 정답이고, 셀에 복사하면 같은 값이 두 벌 생겨 나중에 마스터를 고쳐도
                    //    셀이 옛 값을 계속 이긴다(3단 병합에서 셀이 이긴다).
                    .build());
            for (MasterFromChannelRequest.Component component : spec.getComponents()) {
                productListingProductRepository.save(ProductListingProduct.builder()
                        .productListingOption(listingOption)
                        .product(componentProducts.get(component.getProductId()))
                        .quantity(component.getQuantity())
                        .build());
            }
        }

        // 7) regenerateAssets 를 호출하지 않는다(D5). 이 서비스에는 그 의존성 자체가 없다.
        return ListingMasterCreateResponse.builder()
                .masterProductId(masterId)
                .productListingId(cell.getId())
                .optionCount(request.getOptions().size())
                .status(cell.getStatus())
                .build();
    }

    // ------------------------------------------------------------------ validation

    /**
     * 미리보기·커밋 공통 검증(마켓 조회 <b>전</b>). <b>순서가 규칙이다</b> — 사용자가 수량을 다 채운 뒤에
     * 실패하면 안 되므로 커밋도 같은 순서로 다시 돈다.
     */
    private ChannelContext validate(Platform platform, Long sellerId, String platformProductId) {
        if (!SUPPORTED_PLATFORMS.contains(platform)) {
            throw new IllegalArgumentException(platform + " 가져오기 미지원");
        }
        // 🔴 D17-1: 화이트리스트를 통과하면 어댑터는 seam 에서 얻는다.
        ListingChannel adapter = resolver.resolve(platform);

        Seller seller = sellerRepository.findById(sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Seller", sellerId));
        // 이 기능에는 계정을 읽어올 셀이 아직 없다 → (판매자, 플랫폼)으로 해석한다.
        MarketplaceAccount account = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(sellerId, platform)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", sellerId));
        if (Boolean.FALSE.equals(account.getIsActive())) {
            throw new IllegalArgumentException("비활성 계정");
        }
        // 셀 하나당 마켓 상품 하나(같은 쿠팡 상품이 두 마스터에 붙는 것을 막는다).
        if (productListingRepository.existsByPlatformProductId(platformProductId)) {
            throw new IllegalArgumentException("이미 다른 상품에 연결된 쿠팡 상품입니다");
        }
        return new ChannelContext(seller, account, adapter);
    }

    /** 마켓 조회 1회 + 응답 자체에 대한 검증. */
    private ImportedProduct fetchProduct(ChannelContext ctx, String platformProductId) {
        ImportedProduct fetched = ctx.adapter().fetchProduct(platformProductId, ctx.account());
        if (fetched.options().isEmpty()) {
            throw new IllegalArgumentException("옵션 없는 쿠팡 상품입니다");
        }
        Set<String> names = new HashSet<>();
        for (ImportedProduct.Option option : fetched.options()) {
            // A cell option without a price cannot be margin-checked, and NOT NULL would reject it anyway.
            if (option.salePrice() == null || option.salePrice().compareTo(BigDecimal.ZERO) == 0) {
                throw new IllegalArgumentException("판매가 없는 옵션: " + option.itemName());
            }
            // D7: 마스터 옵션명이 곧 itemName 이라 중복이면 마스터 경로가 뒤에서 터진다 — 사람이 읽을 문구로
            // 앞에서 막는다.
            if (!names.add(trimmed(option.itemName()))) {
                throw new IllegalArgumentException("옵션명이 중복됩니다: " + option.itemName());
            }
        }
        return fetched;
    }

    /**
     * 요청 옵션 집합 == 마켓 옵션 집합. 매칭 키는 {@code platformOptionId}, 없으면(미승인) {@code itemName}.
     * itemName 중복은 {@link #fetchProduct} 가 이미 막았다.
     */
    private Map<MasterFromChannelRequest.OptionSpec, ImportedProduct.Option> matchOptions(
            List<MasterFromChannelRequest.OptionSpec> specs, List<ImportedProduct.Option> marketOptions) {

        Map<String, ImportedProduct.Option> byOptionId = new HashMap<>();
        Map<String, ImportedProduct.Option> byName = new HashMap<>();
        for (ImportedProduct.Option option : marketOptions) {
            if (option.vendorItemId() != null) {
                byOptionId.put(option.vendorItemId(), option);
            }
            byName.put(trimmed(option.itemName()), option);
        }

        Map<MasterFromChannelRequest.OptionSpec, ImportedProduct.Option> pairs = new LinkedHashMap<>();
        Set<ImportedProduct.Option> used = new HashSet<>();
        for (MasterFromChannelRequest.OptionSpec spec : specs) {
            ImportedProduct.Option market = spec.getPlatformOptionId() != null
                    ? byOptionId.get(spec.getPlatformOptionId())
                    : byName.get(trimmed(spec.getItemName()));
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

    /** D6: 각 옵션은 구성상품을 <b>전부</b> 포함하고 모든 수량이 1 이상이어야 한다. */
    private void assertCoversComponents(MasterFromChannelRequest.OptionSpec spec,
                                        Map<Long, Product> componentProducts) {
        Map<Long, Integer> vector = new LinkedHashMap<>();
        for (MasterFromChannelRequest.Component component : spec.getComponents()) {
            vector.put(component.getProductId(), component.getQuantity());
        }
        for (Map.Entry<Long, Product> entry : componentProducts.entrySet()) {
            if (!vector.containsKey(entry.getKey())) {
                throw new IllegalArgumentException("옵션 '" + spec.getItemName() + "' 에 빠진 구성상품이 있습니다: "
                        + label(entry.getValue()));
            }
        }
        for (Map.Entry<Long, Integer> entry : vector.entrySet()) {
            Product product = componentProducts.get(entry.getKey());
            if (product == null) {
                throw new IllegalArgumentException("옵션 '" + spec.getItemName()
                        + "' 에 구성상품이 아닌 물품이 있습니다: " + entry.getKey());
            }
            if (entry.getValue() == null || entry.getValue() < 1) {
                throw new IllegalArgumentException("옵션 '" + spec.getItemName() + "' 의 수량은 1 이상이어야 합니다: "
                        + label(product));
            }
        }
    }

    /** 구성상품이 전부 존재해야 한다(없으면 404). 순서 = 요청 순서. */
    private Map<Long, Product> requireProducts(List<Long> productIds) {
        Set<Long> distinctIds = new LinkedHashSet<>(productIds);
        Map<Long, Product> found = productRepository.findAllById(distinctIds).stream()
                .collect(Collectors.toMap(Product::getId, product -> product));
        Map<Long, Product> ordered = new LinkedHashMap<>();
        for (Long productId : distinctIds) {
            Product product = found.get(productId);
            if (product == null) {
                throw new ResourceNotFoundException("Product", productId);
            }
            ordered.put(productId, product);
        }
        return ordered;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 2609_45/D4-1: 전 옵션에 있고 값까지 같은 키 = 공통(마스터 몫). 그 외는 그 옵션 몫(마스터 옵션 override).
     * 옵션이 1개면 전부 공통이 되어 종전과 같은 결과가 된다 — 특례 분기를 따로 두지 말 것.
     *
     * <p>미리보기와 커밋이 <b>같은 분리 로직</b>을 쓴다(한 곳에 두고 재사용한다).</p>
     */
    private Map<String, String> commonAttributes(List<ImportedProduct.Option> options) {
        Map<String, String> common = new LinkedHashMap<>(options.get(0).attributes());
        for (ImportedProduct.Option option : options) {
            common.entrySet().removeIf(entry ->
                    !Objects.equals(option.attributes().get(entry.getKey()), entry.getValue()));
        }
        return common;
    }

    /** 그 옵션에서 공통이 아닌 항목만(= 마스터 옵션 override 로 갈 몫). */
    private Map<String, String> differingAttributes(ImportedProduct.Option option, Map<String, String> common) {
        Map<String, String> differing = new LinkedHashMap<>();
        option.attributes().forEach((key, value) -> {
            if (!common.containsKey(key)) {
                differing.put(key, value);
            }
        });
        return differing;
    }

    /**
     * D4-2: 고시는 품목군 단위라 옵션에 따라 갈리지 않는다 — 값이 있는 첫 옵션의 것을 상품 공통으로 쓴다.
     * 옵션마다 다르면 그때 옵션으로 내린다(지금 없는 분기를 만들지 않는다).
     */
    private Map<String, String> productNotices(List<ImportedProduct.Option> options) {
        for (ImportedProduct.Option option : options) {
            if (!option.notices().isEmpty()) {
                return new LinkedHashMap<>(option.notices());
            }
        }
        return Map.of();
    }

    /**
     * 2609_45/D2: leafCode → PlatformCategory → CategoryMapping → 우리 표준 Category.
     * 마스터가 아직 없으니 폴백할 카테고리도 없다 — 실패 = 빈 값을 내리고 프론트가 사용자에게 고르게 한다
     * (2609_22/D15 경고 문구는 이 경로에서 쓰지 않는다. 불일치할 대상 자체가 없다).
     */
    private Optional<CategoryMapping> reverseLookup(Platform platform, String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            return Optional.empty();
        }
        return platformCategoryRepository.findByPlatformAndCode(platform, categoryCode)
                .flatMap(pc -> categoryMappingRepository.findByPlatformCategoryId(pc.getId()));
    }

    private String label(Product product) {
        String brand = product.getBrand();
        return (brand == null || brand.isBlank())
                ? product.getProductName() : brand + " " + product.getProductName();
    }

    private String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    /** 미리보기·커밋이 공유하는 검증 결과. */
    private record ChannelContext(Seller seller, MarketplaceAccount account, ListingChannel adapter) {
    }
}
