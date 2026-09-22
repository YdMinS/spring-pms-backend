package com.pms.service.listing;

/**
 * 마스터 상품 <b>물리 삭제</b>의 유일한 진입점(FEATURE_2609_72 / 01).
 *
 * <p>예전 {@code MasterProductService#deleteMasterProduct} 는 {@code active=false} 로 숨기기만 했다 —
 * 그 소프트 삭제 개념은 이 조각에서 사라졌다(PLAN/D1). 마스터를 치우는 길은 이 메서드 하나다.</p>
 */
public interface MasterDeleteService {

    /**
     * 마스터와 그 자식(구성상품·옵션·옵션아이템·사진·zone 매핑) · <b>마켓 미등록</b> 채널 셀을 물리 삭제한다.
     *
     * <p>마켓에 등록된 셀(= {@code platformProductId} 가 있는 셀)이 하나라도 있으면 <b>아무것도 지우지 않고</b>
     * 409 를 던진다 — 사용자가 먼저 화면에서 {@code [연결 해제]}(2609_63) 해야 한다(PLAN/D2).</p>
     *
     * @param masterProductId 지울 마스터 id (없거나 다른 테넌트면 404)
     */
    void deleteMaster(Long masterProductId);
}
