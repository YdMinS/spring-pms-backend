package com.pms.service.listing;

import com.pms.domain.CategoryMapping;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.dto.request.MasterFromChannelPreviewRequest;
import com.pms.dto.response.MasterFromChannelPreviewResponse;
import com.pms.repository.CategoryMappingRepository;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.PlatformCategoryRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「마켓 상품으로 시작」 미리보기(FEATURE_2609_45 / 01 → 2609_79). See {@link MasterFromChannelService}.
 *
 * <p>🔁 <b>2609_79 / UX D70</b>: 이 서비스의 저장 경로(마스터 + 옵션 + 셀 한 번에 만들기)는 <b>없어졌다</b>.
 * 「새 마스터」 는 3단 페이지가 마스터를 먼저 만들고, 이어서 기존 마스터에 붙이기
 * ({@link CoupangListingImportServiceImpl})로 판매상품을 붙인다. 이 서비스는 마스터가 아직 없을 때의
 * <b>읽기</b>(옵션 · 역조회 카테고리 · 공통/옵션별 속성 · 고시)만 한다.</p>
 *
 * <p>계정 판정 · 지원 플랫폼 · 옵션/판매가 검증은 {@link MarketProductAccess} 가 소유한다(UX D47).</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MasterFromChannelServiceImpl implements MasterFromChannelService {

    private final SellerRepository sellerRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ProductListingRepository productListingRepository;
    private final PlatformCategoryRepository platformCategoryRepository;
    private final CategoryMappingRepository categoryMappingRepository;
    // 🔴 D17-1: 어댑터는 여기서 얻는다. CoupangListingAdapter 를 직접 주입하면 seam 이 무의미해진다.
    private final ListingChannelResolver resolver;

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
                .reusesExistingListing(ctx.existing() != null)
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
                // 온보딩(2026-09-19): 마켓 이미지 URL 노출까지가 범위다(적재는 소비자 몫).
                // 썸네일은 마켓 가공본, 상세는 원본에 가까운 사진 — 분리해서 내려준다.
                .thumbnailImages(fetched.thumbnailImages())
                .detailImages(fetched.detailImages())
                .commonAttributes(common)
                .notices(productNotices(fetched.options()))
                .noticeGroup(fetched.noticeGroup())
                .build();
    }

    // ------------------------------------------------------------------ validation

    /**
     * 마켓 조회 <b>전</b> 검증. <b>순서가 규칙이다</b> — 지원 플랫폼 → 어댑터 → 판매자·계정 → 떼어낸 셀.
     */
    private ChannelContext validate(Platform platform, Long sellerId, String platformProductId) {
        MarketProductAccess.requireSupported(platform);
        // 🔴 D17-1: 화이트리스트를 통과하면 어댑터는 seam 에서 얻는다.
        ListingChannel adapter = resolver.resolve(platform);
        MarketProductAccess.SellerAccount sellerAccount = MarketProductAccess.requireActiveAccount(
                sellerId, platform, sellerRepository, marketplaceAccountRepository);
        // 2609_66/D1: 셀이 있어도 <b>떼어낸 셀</b>이면 그 행을 재사용한다(편입과 같은 판정).
        // ✅ findByPlatformProductId 는 파생 쿼리라 Hibernate @TenantId 로 자동 테넌트 필터된다.
        ProductListing existing = productListingRepository.findByPlatformProductId(platformProductId).orElse(null);
        DetachedCellPolicy.requireReusable(existing, platform, sellerId);
        return new ChannelContext(sellerAccount, adapter, existing);
    }

    /** 마켓 조회 1회 + 응답 검증(옵션·판매가 = {@link MarketProductAccess}, 옵션명 중복 = 여기). */
    private ImportedProduct fetchProduct(ChannelContext ctx, String platformProductId) {
        ImportedProduct fetched = MarketProductAccess.fetchPriced(
                ctx.adapter(), platformProductId, ctx.sellerAccount().account());
        Set<String> names = new HashSet<>();
        for (ImportedProduct.Option option : fetched.options()) {
            // D7: 「새 마스터」 의 마스터 옵션명이 곧 itemName 이라(UX D71) 중복이면 마스터 저장이 뒤에서
            // 터진다 — 사람이 읽을 문구로 앞에서 막는다.
            if (!names.add(trimmed(option.itemName()))) {
                throw new IllegalArgumentException("옵션명이 중복됩니다: " + option.itemName());
            }
        }
        return fetched;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 2609_45/D4-1: 전 옵션에 있고 값까지 같은 키 = 공통(마스터 몫). 그 외는 그 옵션 몫(마스터 옵션 override).
     * 옵션이 1개면 전부 공통이 되어 종전과 같은 결과가 된다 — 특례 분기를 따로 두지 말 것.
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
     * 실패 = 빈 값을 내리고 프론트가 사용자에게 고르게 한다.
     */
    private Optional<CategoryMapping> reverseLookup(Platform platform, String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            return Optional.empty();
        }
        return platformCategoryRepository.findByPlatformAndCode(platform, categoryCode)
                .flatMap(pc -> categoryMappingRepository.findByPlatformCategoryId(pc.getId()));
    }

    private String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    /**
     * 미리보기 검증 결과.
     *
     * @param existing 2609_66/D2: 연결이 끊긴 채 남아 있는 같은 마켓 상품의 셀(재사용 대상). 없으면 null.
     */
    private record ChannelContext(MarketProductAccess.SellerAccount sellerAccount, ListingChannel adapter,
                                  ProductListing existing) {
    }
}
