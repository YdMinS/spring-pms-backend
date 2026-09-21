package com.pms.service.listing;

import com.pms.dto.response.ChannelProductDetailResponse;
import com.pms.dto.response.ChannelProductSearchResponse;

/**
 * 마켓 상품을 <b>보기만</b> 하는 창구(2609_67 / PLAN D4). 물품 등록 화면 옆 참고 패널이 유일한 소비자다.
 *
 * <p>🔴 <b>아무 판정도 하지 않는다</b>: 연결 여부·판매자 일치·떼어낸 셀 판정이 전부 없다. 미리보기
 * ({@link MasterFromChannelService#preview})는 {@code DetachedCellPolicy} 로 <b>이미 마스터에 붙어 있는
 * 상품을 400 으로 막는데</b>, 참고 패널은 바로 그 "이미 파는 상품"을 보려고 여는 화면이라 그 판정이 정확히
 * 반대로 걸린다. 그래서 미리보기를 재사용하지 않고 이 창구가 따로 있다.</p>
 *
 * <p>저장 0회 · 새 테이블·컬럼 0. 하는 검증은 계정 해석(판매자+플랫폼, 비활성 계정 400)뿐이다.</p>
 */
public interface ChannelProductLookupService {

    /**
     * 상품명으로 이 판매자의 마켓 상품을 검색한다(마켓 GET 1회).
     *
     * <p>🔴 결과에 <b>사진이 없다</b> — 쿠팡 목록 응답에 이미지 필드가 없다. 결과 없음은 빈 목록이지 예외가
     * 아니다. 20자 초과 검색어는 조용히 잘리지 않고 400 이다(어댑터가 지킨다).</p>
     *
     * @param sellerId  판매자 id
     * @param platform  플랫폼 키(예 {@code COUPANG})
     * @param name      검색어(1~20자)
     * @param nextToken 이어보기 키 — 첫 페이지는 null
     */
    ChannelProductSearchResponse search(Long sellerId, String platform, String name, String nextToken);

    /**
     * 마켓 상품 한 건을 그대로 읽는다(사진·옵션·속성·고시).
     *
     * <p>⚠️ 쿠팡 GET 은 <b>1~2회</b>다 — 속성값에 {@code 숫자+접미사} 가 하나라도 있으면 어댑터가 카테고리
     * 메타를 한 번 더 읽는다. 그래도 그 경로를 쓴다(직접 파싱하면 단위 처리가 두 벌이 된다).</p>
     *
     * <p>🔴 옵션 0개·판매가 0 을 예외로 만들지 않는다 — 보기만 하는 화면에서는 임시저장 상품도 보여야 한다.</p>
     *
     * @param sellerId          판매자 id
     * @param platform          플랫폼 키(예 {@code COUPANG})
     * @param platformProductId 마켓 상품 id (쿠팡 sellerProductId)
     */
    ChannelProductDetailResponse detail(Long sellerId, String platform, String platformProductId);
}
