package com.pms.service.listing;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.dto.response.ChannelProductDetailResponse;
import com.pms.dto.response.ChannelProductSearchResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.SellerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link ChannelProductLookupService} 구현(2609_67 / 01).
 *
 * <p>계정 해석은 {@link MasterFromChannelServiceImpl#validate} 의 <b>앞부분만</b> 가져왔다:
 * 플랫폼 화이트리스트 → 어댑터 해석 → 판매자 → (판매자, 플랫폼) 계정 → 비활성 계정 400.
 * 🔴 그 아래의 셀 조회와 {@code DetachedCellPolicy.requireReusable} 은 <b>의도적으로 빼 놓았다</b> —
 * 그것을 부르면 "이미 파는 상품"이 전부 400 이 되어 이 기능이 죽는다(PLAN/D4). 그래서 이 서비스는
 * {@code ProductListingRepository} 를 <b>주입조차 하지 않는다</b>(판정할 수단 자체를 갖지 않는다).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChannelProductLookupServiceImpl implements ChannelProductLookupService {

    /**
     * 읽기 경로를 실제로 구현한 플랫폼. ⚠️ 어댑터 기본 구현의 {@code UnsupportedOperationException} 은
     * 전역 핸들러가 없어 500 이 되므로 여기서 400 으로 막는다. 🔴 네이버가 붙는 날 바뀌는 것은 이 한 줄이어야 한다.
     */
    private static final Set<Platform> SUPPORTED_PLATFORMS = Set.of(Platform.COUPANG);

    private final SellerRepository sellerRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    // 🔴 어댑터는 seam 에서 얻는다. CoupangListingAdapter 를 직접 주입하면 seam 이 무의미해진다.
    private final ListingChannelResolver resolver;

    @Override
    public ChannelProductSearchResponse search(Long sellerId, String platform, String name, String nextToken) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("검색어를 입력하세요");
        }
        ChannelContext ctx = resolveAccount(platform, sellerId);
        ChannelProductPage page = ctx.adapter().searchProducts(name.trim(), nextToken, ctx.account());

        return ChannelProductSearchResponse.builder()
                .items(page.items().stream()
                        .map(item -> ChannelProductSearchResponse.Item.builder()
                                .platformProductId(item.platformProductId())
                                .productName(item.productName())
                                // 어댑터가 이미 mapStatus 로 변환한 값이다 — 여기서 다시 변환하지 않는다.
                                .status(item.status())
                                .brand(item.brand())
                                .createdAt(item.createdAt())
                                .build())
                        .collect(Collectors.toList()))
                .nextToken(page.nextToken())
                .build();
    }

    @Override
    public ChannelProductDetailResponse detail(Long sellerId, String platform, String platformProductId) {
        ChannelContext ctx = resolveAccount(platform, sellerId);
        // 🔴 옵션 0개·판매가 0 을 걸러내지 않는다(MasterFromChannelServiceImpl.fetchProduct 의 그 가드들은
        //    마스터를 만들 때만 의미가 있다). 보기만 하는 화면에서는 임시저장 상품도 보여야 한다.
        ImportedProduct fetched = ctx.adapter().fetchProduct(platformProductId, ctx.account());

        return ChannelProductDetailResponse.builder()
                .platformProductId(platformProductId)
                .productName(fetched.productName())
                .brand(fetched.brand())
                .status(fetched.status())
                .categoryCode(fetched.categoryCode())
                .noticeGroup(fetched.noticeGroup())
                .notices(productNotices(fetched.options()))
                // 🔴 두 목록을 합치지 않는다 — 썸네일은 마켓 가공본, 상세는 원본에 가까운 사진이다.
                .thumbnailImages(fetched.thumbnailImages())
                .detailImages(fetched.detailImages())
                .options(fetched.options().stream()
                        .map(option -> ChannelProductDetailResponse.Option.builder()
                                .itemName(option.itemName())
                                .salePrice(option.salePrice())
                                .stockQuantity(option.stockQuantity())
                                // 공통 속성 분리는 하지 않는다 — 옵션별 속성을 그대로 내린다.
                                .attributes(option.attributes())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    // ------------------------------------------------------------------ helpers

    /** 계정 해석만 한다 — 셀 조회·재사용 판정은 <b>일부러</b> 없다(PLAN/D4). */
    private ChannelContext resolveAccount(String platform, Long sellerId) {
        Platform resolved = Platform.from(platform);
        if (!SUPPORTED_PLATFORMS.contains(resolved)) {
            throw new IllegalArgumentException(resolved + " 상품 조회 미지원");
        }
        ListingChannel adapter = resolver.resolve(resolved);
        // 판매자 존재 확인(404) — 이 기능에는 계정을 읽어올 셀이 없어 (판매자, 플랫폼)으로 해석한다.
        sellerRepository.findById(sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Seller", sellerId));
        MarketplaceAccount account = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(sellerId, resolved)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", sellerId));
        if (Boolean.FALSE.equals(account.getIsActive())) {
            throw new IllegalArgumentException("비활성 계정");
        }
        return new ChannelContext(account, adapter);
    }

    /** 고시는 품목군 단위라 옵션에 따라 갈리지 않는다 — 값이 있는 첫 옵션의 것을 상품 레벨로 올린다. */
    private Map<String, String> productNotices(List<ImportedProduct.Option> options) {
        for (ImportedProduct.Option option : options) {
            if (!option.notices().isEmpty()) {
                return new LinkedHashMap<>(option.notices());
            }
        }
        return Map.of();
    }

    private record ChannelContext(MarketplaceAccount account, ListingChannel adapter) {}
}
