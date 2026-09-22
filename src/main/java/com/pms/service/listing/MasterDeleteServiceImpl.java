package com.pms.service.listing;

import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.domain.MasterProductOption;
import com.pms.domain.ProductListing;
import com.pms.exception.BusinessException;
import com.pms.exception.MasterProductInUseException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.MasterProductOptionItemRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.MasterProductImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 마스터 물리 삭제(FEATURE_2609_72 / 01).
 *
 * <p><b>용도</b>: 마스터 상품을 실제로 지우는 <b>유일한 진입점</b>이다. 마스터 행 + 자식(구성상품 · 옵션 ·
 * 옵션아이템 · 사진 · zone 매핑) + <b>마켓 미등록</b> 채널 셀을 한 트랜잭션에서 물리 삭제한다. 예전의
 * {@code active=false} 숨김은 사라졌다(PLAN/D1) — 마스터를 치우는 길은 여기 하나다.</p>
 *
 * <p>🔴 <b>셀 자식을 직접 지우지 않는다.</b> 셀 삭제는 {@link ChannelLinkService#deleteDraftChannel} 에
 * 위임한다(PLAN/D5). 셀에 무엇이 딸려 있는지(구성 · 옵션 · 자동생성물 · 태그이력)는 한 곳에만 적혀 있어야
 * 한다 — 여기서 같은 목록을 한 번 더 적으면 셀 스키마가 바뀔 때(2609_71/04 의 {@code product_listing_product}
 * 제거) 이 파일도 같이 고쳐야 하고, 한쪽만 고치면 FK 위반이 난다.</p>
 *
 * <p>🔴 <b>마켓에 등록된 셀은 이 서비스로 지울 수 없다</b> — {@code [연결 해제]}(2609_63) 로 마스터에서 떼어낸
 * 뒤에만 마스터를 지울 수 있다(PLAN/D2). "판매상품은 남는다"는 규칙이 지켜지는 자리가 바로 이 가드다:
 * 해제된 셀은 {@code master_product_id = null} 이라 더 이상 이 마스터의 셀이 아니고, 마스터를 지워도 계속
 * 팔린다.</p>
 *
 * <p>⚠️ 사진 삭제는 {@link MasterProductImageService#removeFromPool} 이 하는데, 그것은 <b>편집본 S3 파일을
 * 실제로 지운다</b>. 이미 해제된 셀의 자동생성 상세가 그 URL 을 가리키고 있으면 마켓 상세 이미지가 깨질 수
 * 있다(PLAN 「알려진 대가」). 가드는 이 경우를 잡지 못한다 — 해제된 셀은 이 마스터의 셀이 아니기 때문이다.
 * 실제 사고가 나면 {@code removeFromPool} 대신 행만 지우도록 바꾸는 것이 1줄 수정이다.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MasterDeleteServiceImpl implements MasterDeleteService {

    private final MasterProductRepository masterProductRepository;
    private final ProductListingRepository productListingRepository;
    private final MasterProductComponentRepository componentRepository;
    private final MasterProductOptionRepository optionRepository;
    private final MasterProductOptionItemRepository optionItemRepository;
    private final MasterProductImageRepository imageRepository;
    private final MasterProductImageService masterProductImageService;
    private final ChannelLinkService channelLinkService;

    /**
     * 실행 순서가 곧 FK 순서다: 가드 → 셀 → 옵션아이템 → 옵션 → 구성상품 → 사진 → 마스터.
     *
     * <p>🔴 되돌릴 수 없으므로 마스터를 지우기 직전에 지운 개수를 INFO 로그에 남긴다(PLAN/D10) — 지운 뒤에는
     * 셀 수도 없다.</p>
     */
    @Override
    @Transactional
    public void deleteMaster(Long masterId) {
        MasterProduct master = masterProductRepository.findScopedById(masterId)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", masterId));

        // 1) 가드 — 마켓에 등록된 셀이 하나라도 있으면 아무것도 지우지 않는다 (D2·D4)
        List<ProductListing> cells = productListingRepository.findByMasterProductId(masterId);
        long onMarket = cells.stream().filter(MasterDeleteServiceImpl::isOnMarket).count();
        if (onMarket > 0) {
            throw new MasterProductInUseException(onMarket);
        }

        // 2) 마켓 미등록 셀 — 2609_63 경로 재사용(구성 → 옵션 → 자동생성물 → 태그이력 → 셀, 끝에 flush)
        //    (D3·D5) ⚠️ 셀에 기록이 남아 있으면 이 호출이 409 를 던진다 — 그대로 올린다.
        for (ProductListing cell : cells) {
            channelLinkService.deleteDraftChannel(masterId, cell.getId());
        }

        // 3) 옵션 아이템 → 옵션. 아이템 삭제는 @Modifying 벌크라 즉시 실행된다(2번의 flush 이후여야 한다).
        List<MasterProductOption> options = optionRepository.findByMasterProductId(masterId);
        for (MasterProductOption option : options) {
            optionItemRepository.deleteByOptionId(option.getId());
        }
        optionRepository.deleteAll(options);

        // 4) 구성상품 — 개수는 로그(D10)용으로 지우기 전에 센다.
        int componentCount = componentRepository.findByMasterProductId(masterId).size();
        componentRepository.deleteByMasterProductId(masterId);
        componentRepository.flush();     // 🔴 마스터 DELETE 보다 자식 DELETE 가 먼저 DB 에 닿게 한다

        // 5) 사진 — zone 매핑 → 행 → S3(편집본만, 실패는 로그) (D6)
        // 🔴 자식 중 **맨 마지막**이다. removeFromPool 은 커밋 전에 S3 파일을 지우므로, 뒤에서 롤백이 나면
        //    DB 는 되살아나고 파일만 영구히 사라진다. 뒤에 남는 것을 마스터 DELETE 한 줄로 줄인다.
        List<MasterProductImage> images =
                imageRepository.findByMasterProductIdOrderBySortOrderAsc(masterId);
        for (MasterProductImage image : images) {
            masterProductImageService.removeFromPool(masterId, image.getId());
        }

        log.info("[MASTER-DELETE] id={} name='{}' cells={} options={} components={} images={}",
                masterId, master.getName(), cells.size(), options.size(), componentCount, images.size());

        // 6) 마스터
        try {
            masterProductRepository.delete(master);
            masterProductRepository.flush();   // 🔴 여기서 터뜨려야 아래 catch 가 잡는다 (D9)
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException("기록이 남아 있어 삭제할 수 없습니다", HttpStatus.CONFLICT);
        }
    }

    /** 삭제를 막는 셀 = 마켓에 상품 ID 가 있는 셀. 상태는 보지 않는다 (D4). */
    private static boolean isOnMarket(ProductListing cell) {
        return cell.getPlatformProductId() != null && !cell.getPlatformProductId().isBlank();
    }
}
