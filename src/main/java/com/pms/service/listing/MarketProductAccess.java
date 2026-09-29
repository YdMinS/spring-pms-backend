package com.pms.service.listing;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.Seller;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.SellerRepository;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 마켓 상품을 읽기 전에 공통으로 하는 판정(FEATURE_2609_79 / UX D47). 「마켓 상품으로 시작」 미리보기
 * ({@link MasterFromChannelServiceImpl})와 기존 마스터에 붙이기({@link CoupangListingImportServiceImpl})가
 * <b>같은 함수</b>를 부른다 — 두 서비스가 같은 규칙을 손으로 맞춰 두던 복사본을 한 곳으로 모았다.
 *
 * <p>상태 없는 정적 유틸이다({@link DetachedCellPolicy} · {@code ListingStockPolicy} 와 같은 모양). 저장소는
 * 호출하는 서비스가 넘긴다 — 판정을 부르는 순서(마켓 조회 <b>전</b> 검증)는 각 서비스가 정한다.</p>
 *
 * <p>🔴 문구를 바꾸지 말 것 — 화면이 HTTP 상태 + 문구 일부로 조치 안내를 덧붙인다.</p>
 * <p>🔴 네이버가 붙는 날 바뀌는 것은 {@link #SUPPORTED_PLATFORMS} 한 줄이어야 한다(UX D64).</p>
 */
public final class MarketProductAccess {

    /**
     * {@link ListingChannel#fetchProduct} 를 실제로 구현한 플랫폼. ⚠️ 어댑터 기본 구현의
     * {@code UnsupportedOperationException} 은 전역 핸들러가 없어 500 이 되므로 여기서 400 으로 막는다.
     */
    public static final Set<Platform> SUPPORTED_PLATFORMS = Set.of(Platform.COUPANG);

    private MarketProductAccess() {
    }

    /** 마켓 상품 읽기를 지원하지 않는 플랫폼이면 400. */
    public static void requireSupported(Platform platform) {
        if (!SUPPORTED_PLATFORMS.contains(platform)) {
            throw new IllegalArgumentException(platform + " 가져오기 미지원");
        }
    }

    /**
     * (판매자, 플랫폼)의 활성 계정. 판매자·계정이 없으면 404, 비활성 계정이면 400.
     *
     * @return 판매자 + 계정
     */
    public static SellerAccount requireActiveAccount(Long sellerId, Platform platform,
                                                     SellerRepository sellerRepository,
                                                     MarketplaceAccountRepository marketplaceAccountRepository) {
        Seller seller = sellerRepository.findById(sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Seller", sellerId));
        MarketplaceAccount account = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(sellerId, platform)
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount", sellerId));
        if (Boolean.FALSE.equals(account.getIsActive())) {
            throw new IllegalArgumentException("비활성 계정");
        }
        return new SellerAccount(seller, account);
    }

    /**
     * 마켓 조회 1회 + 응답 자체 검증: 옵션이 1개 이상이고 모든 옵션에 판매가가 있어야 한다.
     * A cell option without a price cannot be margin-checked, and NOT NULL would reject it anyway.
     */
    public static ImportedProduct fetchPriced(ListingChannel adapter, String platformProductId,
                                              MarketplaceAccount account) {
        ImportedProduct fetched = adapter.fetchProduct(platformProductId, account);
        if (fetched.options().isEmpty()) {
            throw new IllegalArgumentException("옵션 없는 쿠팡 상품입니다");
        }
        for (ImportedProduct.Option option : fetched.options()) {
            if (option.salePrice() == null || option.salePrice().compareTo(BigDecimal.ZERO) == 0) {
                throw new IllegalArgumentException("판매가 없는 옵션: " + option.itemName());
            }
        }
        return fetched;
    }

    /** {@link #requireActiveAccount} 의 결과. */
    public record SellerAccount(Seller seller, MarketplaceAccount account) {
    }
}
