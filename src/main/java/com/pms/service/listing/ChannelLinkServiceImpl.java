package com.pms.service.listing;

import com.pms.domain.ListingStatus;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.exception.BusinessException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.exception.ValidationException;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ProductListingTagRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 채널 연결 해제 · 미전송 채널 삭제(FEATURE_2609_63 / 01).
 *
 * <p><b>연결 해제는 로컬 정리다.</b> 쿠팡 호출 0회 — 마켓에는 아무 변화가 없다. 주문·문의·정산 기록은 셀에
 * 그대로 남는다({@code customer_inquiry} · {@code order_line} · {@code settlement_line} ·
 * {@code price_change_log} 가 셀/셀옵션을 FK 로 문다). 그래서 이 조각은 행을 지우지 않고 FK 두 개만 비운다
 * (PLAN/D2·D3).</p>
 *
 * <p>🔴 해제 때 <b>BOM({@code product_listing_product})·자동생성물·태그·배송 override·옵션의 가격/재고/
 * 마켓 식별자/승인상태/active 를 건드리지 않는다.</b> BOM 을 지우면 「미연결 셀 → 마스터 생성」이 막힌다
 * ({@code ListingMasterCreateServiceImpl} 의 "구성품이 없는 옵션").</p>
 *
 * <p>⚠️ {@code MasterProductServiceImpl} 에 넣지 않는다 — 그 클래스는 이미 1300줄이 넘는다.</p>
 */
@Service
@RequiredArgsConstructor
public class ChannelLinkServiceImpl implements ChannelLinkService {

    private final MasterProductRepository masterProductRepository;
    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final GeneratedProductDataRepository generatedProductDataRepository;
    private final ProductListingTagRevisionRepository productListingTagRevisionRepository;

    /**
     * 마켓 등록 셀을 마스터에서 떼어낸다: {@code ProductListing.masterProduct} → null,
     * 그 셀의 모든 {@code ProductListingOption.masterProductOption} → null.
     *
     * <p>옵션 FK 의 null 은 새 상태가 아니다 — FK 가 원래 {@code ON DELETE SET NULL} 이고
     * {@code ProductListingOption#isChannelOnly()} 가 그 상태를 이미 정의한다(2609_22/D2).</p>
     *
     * <p>🔴 DRAFT 셀({@code platformProductId == null})은 떼면 어디에도 못 붙는 미아가 된다 — 편입이 마켓
     * 상품 ID 로 찾기 때문이다(PLAN/D4). 미전송 채널은 {@link #deleteDraftChannel} 로 지운다.</p>
     */
    @Override
    @Transactional
    public void unlink(Long masterProductId, Long productListingId) {
        ProductListing listing = requireCellOfMaster(masterProductId, productListingId);

        if (listing.getPlatformProductId() == null || listing.getPlatformProductId().isBlank()) {
            throw new ValidationException("마켓에 등록되지 않은 채널은 연결 해제할 수 없습니다");
        }

        List<ProductListingOption> options =
                productListingOptionRepository.findByProductListingId(productListingId);
        productListingOptionRepository.saveAll(options.stream()
                .map(option -> option.toBuilder().masterProductOption(null).build())
                .toList());

        productListingRepository.save(listing.toBuilder().masterProduct(null).build());
    }

    /**
     * 미전송(DRAFT) 채널 셀을 물리 삭제한다 — 구성 → 옵션 → 자동생성물 → 태그이력 → 셀 순서다(자식 먼저).
     *
     * <p>DRAFT 셀에는 PLAN/D2 가 지목한 기록이 <b>구조적으로 붙을 수 없다</b>:</p>
     * <ul>
     *   <li>주문({@code order_line}) · 정산({@code settlement_line}) · 고객문의({@code customer_inquiry}) 는
     *       전부 <b>마켓 옵션/상품 ID</b> 로 연결된다. DRAFT 는 그 ID 가 없다.</li>
     *   <li>가격이력({@code price_change_log}) 은 {@code PriceHistoryRecorder} 가 DRAFT 셀을 명시적으로
     *       제외한다.</li>
     * </ul>
     *
     * <p>🔴 그래도 안전망으로 삭제 직후 {@code flush()} 한다 — {@code delete()} 는 지연 실행이라 flush 가
     * 없으면 FK 위반이 <b>커밋 시점</b>(이 메서드 밖)에 터져 500 이 된다.</p>
     *
     * <p>🔴 S3 썸네일 <b>파일</b>은 지우지 않는다(행만 삭제). 고아 파일 정리는 별건이고, 잘못 지우면 404 다.</p>
     */
    @Override
    @Transactional
    public void deleteDraftChannel(Long masterProductId, Long productListingId) {
        ProductListing listing = requireCellOfMaster(masterProductId, productListingId);

        // 🔴 두 조건을 모두 본다 — 하나만 보면 "상태만 DRAFT 인데 상품 ID 가 남은" 행이 통과한다.
        boolean registered = listing.getPlatformProductId() != null
                && !listing.getPlatformProductId().isBlank();
        if (registered || listing.getStatus() != ListingStatus.DRAFT) {
            throw new ValidationException("마켓에 등록된 채널은 삭제할 수 없습니다. 연결 해제 후 정리하세요");
        }

        productListingProductRepository.deleteByProductListingId(productListingId);
        productListingOptionRepository.deleteByProductListingId(productListingId);
        generatedProductDataRepository.deleteByProductListingId(productListingId);
        productListingTagRevisionRepository.deleteByProductListing_Id(productListingId);
        try {
            productListingRepository.delete(listing);
            productListingRepository.flush();   // 🔴 여기서 터뜨려야 아래 catch 가 잡는다
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException("기록이 남아 있어 삭제할 수 없습니다", HttpStatus.CONFLICT);
        }
    }

    /**
     * 해제·삭제 공통 검증: 마스터·셀이 이 테넌트에 있고, 그 셀이 <b>이 마스터의</b> 채널인가.
     *
     * <p>⚠️ 마스터 비교는 {@code listing.getMasterProduct()} 의 <b>id 만</b> 읽는다 — LAZY 프록시를 깨우지
     * 않는다(같은 패턴이 {@code ListingOptionsResponse.linkedMaster} 에 이미 있다).</p>
     */
    private ProductListing requireCellOfMaster(Long masterProductId, Long productListingId) {
        masterProductRepository.findScopedById(masterProductId)
                .orElseThrow(() -> new ResourceNotFoundException("MasterProduct", masterProductId));
        ProductListing listing = productListingRepository.findScopedById(productListingId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", productListingId));

        if (listing.getMasterProduct() == null
                || !masterProductId.equals(listing.getMasterProduct().getId())) {
            throw new ValidationException("이 마스터의 채널이 아닙니다");
        }
        return listing;
    }
}
