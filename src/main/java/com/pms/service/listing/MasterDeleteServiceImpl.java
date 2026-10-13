package com.pms.service.listing;

import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.domain.MasterProductOption;
import com.pms.domain.ProductListing;
import com.pms.exception.BusinessException;
import com.pms.exception.MasterProductInUseException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterProductComponentRepository;
import com.pms.repository.MasterPlatformCategoryRepository;
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
 * Master physical delete (FEATURE_2609_72 / 01).
 *
 * <p><b>Purpose</b>: the <b>single entry point</b> that really deletes a master product. It physically deletes
 * the master row + its children (components · options · option items · own platform categories
 * (FEATURE_2610_12) · photos · zone mappings) + the channel cells <b>not on a market</b>, in one transaction.
 * The old {@code active=false} hiding is gone (PLAN/D1) — this is the only way to remove a master.</p>
 *
 * <p>🔴 <b>Never delete a cell's children directly.</b> Cell deletion is delegated to
 * {@link ChannelLinkService#deleteDraftChannel} (PLAN/D5). What hangs off a cell (components · options ·
 * generated assets · tag history) must be written down in one place only — listing it again here means this
 * file must change too whenever the cell schema changes (the {@code product_listing_product} removal of
 * 2609_71/04), and changing only one side is an FK violation.</p>
 *
 * <p>🔴 <b>A cell registered on a market cannot be deleted by this service</b> — the master can be deleted only
 * after that cell is detached from it with {@code [연결 해제]} (2609_63) (PLAN/D2). This guard is exactly where
 * the "the marketplace product stays" rule holds: a detached cell has {@code master_product_id = null}, so it is
 * no longer this master's cell and keeps selling after the master is deleted.</p>
 *
 * <p>⚠️ Photos are deleted by {@link MasterProductImageService#removeFromPool}, which <b>really deletes the
 * edited S3 files</b>. If the generated detail of an already-detached cell points at such a URL, the market detail
 * image can break (PLAN 「알려진 대가」). The guard cannot catch this — a detached cell is not this master's cell.
 * If it really happens, making {@code removeFromPool} delete only the rows instead is a one-line change.</p>
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
    private final MasterPlatformCategoryRepository masterPlatformCategoryRepository;
    private final MasterProductImageService masterProductImageService;
    private final ChannelLinkService channelLinkService;

    /**
     * The execution order is the FK order: guard → cells → option items → options → components + own platform
     * categories → photos → master.
     *
     * <p>🔴 It cannot be undone, so the deleted counts are logged at INFO right before the master is deleted
     * (PLAN/D10) — afterwards there is nothing left to count.</p>
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

        // 4) Components — counted before the delete for the log (D10) — then the own platform categories
        //    (FEATURE_2610_12 / D40: they FK-reference the master).
        int componentCount = componentRepository.findByMasterProductId(masterId).size();
        componentRepository.deleteByMasterProductId(masterId);
        masterPlatformCategoryRepository.deleteByMasterProductId(masterId);
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
