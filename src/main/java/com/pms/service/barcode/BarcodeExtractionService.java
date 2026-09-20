package com.pms.service.barcode;

import com.pms.dto.request.BarcodeExtractionRequest;
import com.pms.dto.response.BarcodeExtractionResult;

/**
 * 물품 사진에서 바코드를 읽어 {@code products.barcode_id} 를 채운다 (FEATURE_2609_65).
 *
 * <p>구현은 {@link BarcodeExtractionServiceImpl} — 그 클래스 주석에 상태 6종의 뜻과
 * {@code @Transactional} 을 쓰지 않는 이유가 적혀 있다.</p>
 */
public interface BarcodeExtractionService {

    /**
     * 요청한 물품들의 사진을 순서대로 읽어 바코드를 채운다. 물품별로 각각 커밋되며,
     * 한 물품의 실패가 다른 물품의 저장을 되돌리지 않는다.
     *
     * @throws com.pms.exception.ResourceNotFoundException 요청 id 중 하나라도 이 테넌트의 물품이 아닐 때
     *                                                     (사진을 한 장도 읽기 전에 판정한다)
     */
    BarcodeExtractionResult extract(BarcodeExtractionRequest request);
}
