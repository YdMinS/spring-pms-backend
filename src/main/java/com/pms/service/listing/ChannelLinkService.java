package com.pms.service.listing;

/**
 * 채널 셀의 <b>마스터 연결</b>을 다루는 쓰기 전용 서비스(FEATURE_2609_63 / 01).
 *
 * <p>잘못된 마스터에 붙은 판매상품을 ① 마켓 등록분이면 <b>떼어내고</b>({@link #unlink}) ② 미전송분이면
 * <b>지운다</b>({@link #deleteDraftChannel}). 떼어낸 셀은 올바른 마스터의 [쿠팡 상품 가져오기] 가 그 행을
 * 그대로 다시 쓴다({@code CoupangListingImportServiceImpl}).</p>
 *
 * <p>🔴 되돌릴 수 있는 정도가 달라 두 동작을 한 메서드·한 엔드포인트로 합치지 않는다(PLAN/D13).</p>
 */
public interface ChannelLinkService {

    /**
     * 마켓에 등록된 채널 셀을 마스터에서 떼어낸다 — FK 두 개(셀→마스터, 셀 옵션→마스터 옵션)만 null 이 된다.
     *
     * @param masterProductId 셀이 현재 붙어 있는 마스터 id
     * @param productListingId 떼어낼 셀 id
     */
    void unlink(Long masterProductId, Long productListingId);

    /**
     * <b>마켓 미등록</b> 채널 셀을 물리 삭제한다 — 마켓 상품 ID 가 있는 셀은 400 으로 막는다.
     *
     * <p>⚠️ 판정은 마켓 상품 ID 유무 하나다. 상태({@code status})는 보지 않는다(2609_72/D12) — 메서드·경로
     * 이름의 'Draft' 는 하위호환으로 남긴 것이다.</p>
     *
     * @param masterProductId 셀이 붙어 있는 마스터 id
     * @param productListingId 지울 셀 id
     */
    void deleteDraftChannel(Long masterProductId, Long productListingId);
}
