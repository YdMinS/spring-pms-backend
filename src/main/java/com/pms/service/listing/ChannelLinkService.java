package com.pms.service.listing;

import com.pms.dto.response.DetachedListingResponse;

import java.util.List;

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

    /**
     * 2609_74/D1·D14: 마스터 연결이 끊긴 판매상품을 <b>한 계정(판매자·플랫폼)</b> 범위로 찾는다 — 최신순 20건.
     * 읽기 전용이다. 다시 붙이는 일은 가져오기({@code CoupangListingImportService})가 한다.
     *
     * @param masterProductId 붙이려는 마스터(존재·테넌트 확인용). 결과를 이 값으로 거르지 않는다
     * @param keyword         null·공백 = 전체. 이름 부분일치 또는 마켓 상품 ID 완전일치
     */
    List<DetachedListingResponse> findDetached(Long masterProductId, Long sellerId, String platform, String keyword);
}
