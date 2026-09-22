package com.pms.service.listing;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.dto.response.ChannelImageResponse;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.exception.ValidationException;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.ProductListingRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * See {@link ListingChannelImageService}.
 *
 * <p>새 조회 로직을 만들지 않았다 — 마켓 상품을 읽는 다른 경로와 같은 네 걸음
 * (셀 테넌트 스코프 조회 → 플랫폼 화이트리스트 → 계정 해석 → {@link ListingChannel#fetchProduct})을 그대로
 * 따른다. 다른 점은 <b>범위</b>뿐이다: 이쪽은 이미지 URL 만 읽으므로 옵션·구성품 검증이 하나도 필요 없다.</p>
 *
 * <p>🔴 {@code masterProduct} 연결 여부를 검사하지 않는다. 이 경로가 필요로 하는 것은
 * {@code platformProductId} 하나뿐이고, 연결 여부는 "마켓에 이 상품이 있는가" 와 아무 상관이 없다 —
 * 없는 조건을 걸면 연결 직전의 셀을 이유 없이 막는다.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingChannelImageServiceImpl implements ListingChannelImageService {

    private static final Logger log = LoggerFactory.getLogger(ListingChannelImageServiceImpl.class);

    /**
     * {@link ListingChannel#fetchProduct} 를 실제로 구현한 플랫폼. ⚠️ 어댑터 기본 구현의
     * {@code UnsupportedOperationException} 은 전역 핸들러가 없어 <b>500</b> 이 되므로 여기서 400 으로 막는다
     * ({@code CoupangListingImportServiceImpl} 과 같은 이유·같은 방식).
     */
    private static final Set<Platform> SUPPORTED_PLATFORMS = Set.of(Platform.COUPANG);

    private final ProductListingRepository productListingRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final ListingChannelResolver resolver;

    @Override
    public ChannelImageResponse images(Long listingId) {
        // 1. 셀 — findScopedById 가 테넌트 필터를 탄다. 다른 테넌트의 id = empty = 404.
        ProductListing cell = productListingRepository.findScopedById(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", listingId));

        // 2. 플랫폼 (미지원 400)
        if (!SUPPORTED_PLATFORMS.contains(cell.getPlatform())) {
            throw new ValidationException("마켓 이미지 조회를 지원하지 않는 플랫폼입니다: " + cell.getPlatform());
        }

        // 3. 마켓 상품 ID — 없으면 조회할 대상 자체가 없다.
        if (cell.getPlatformProductId() == null || cell.getPlatformProductId().isBlank()) {
            throw new ValidationException("마켓 상품 ID가 없는 판매상품입니다");
        }

        // 4. 계정 (없음 404 / 비활성 400) — ListingRegistrationServiceImpl.resolveAccount 와 같은 규칙
        MarketplaceAccount account = marketplaceAccountRepository
                .findBySeller_IdAndPlatform(cell.getSeller().getId(), cell.getPlatform())
                .orElseThrow(() -> new ResourceNotFoundException("MarketplaceAccount",
                        cell.getSeller().getId()));
        if (Boolean.FALSE.equals(account.getIsActive())) {
            throw new ValidationException("비활성 계정");
        }

        // 5. 마켓 읽기 1회. 🔴 재시도·sleep 을 만들지 말 것 — 속도 제한과 429 쿨다운은 CoupangApiClientImpl
        //    이 이미 처리한다. BusinessException(=CoupangRateLimitedException 의 429 포함)은 그대로 흘려
        //    보내고, 그 밖의 실패만 400 으로 바꾼다(쿨다운을 400 으로 덮으면 "언제 다시" 가 사라진다).
        ImportedProduct fetched;
        try {
            fetched = resolver.resolve(cell.getPlatform())
                    .fetchProduct(cell.getPlatformProductId(), account);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("마켓 이미지 조회 실패 listingId={} platformProductId={}",
                    listingId, cell.getPlatformProductId(), e);
            throw new ValidationException("쿠팡에서 상품을 찾을 수 없습니다 — 상품 ID를 확인하세요");
        }

        // 6. URL 만 그대로 흘린다. 이미지 없음은 예외가 아니라 빈 목록이다(ImportedProduct 가 보장한다).
        //    🔴 두 목록을 합치지 말 것 — 썸네일은 마켓 가공본이다.
        return ChannelImageResponse.builder()
                .productListingId(cell.getId())
                .platform(cell.getPlatform().name())
                .platformProductId(cell.getPlatformProductId())
                .productName(fetched.productName())
                .thumbnailImages(fetched.thumbnailImages())
                .detailImages(fetched.detailImages())
                .build();
    }
}
