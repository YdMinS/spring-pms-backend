package com.pms.service.listing;

import com.pms.dto.request.ListingImportPreviewRequest;
import com.pms.dto.request.ListingImportRequest;
import com.pms.dto.response.ChannelAddResponse;
import com.pms.dto.response.ListingImportPreviewResponse;

/**
 * 마켓에 이미 등록된 상품을 기존 마스터의 <b>채널 셀</b>로 편입한다(FEATURE_2609_22 / D8).
 *
 * <p>2단계다: {@link #preview} 는 마켓을 1회 조회해 매핑 판정과 입력 줄만 돌려주고 <b>아무것도 저장하지
 * 않는다</b>. {@link #importListing} 은 사용자가 채운 구성으로 마스터 옵션·셀·셀 옵션·셀 BOM 을 만들고,
 * 그것이 <b>커밋된 뒤</b> 자산을 생성한다(자산 생성 실패가 셀을 되돌리지 않는다).</p>
 *
 * <p><b>필수 규칙</b></p>
 * <ul>
 *   <li>⚠️ 이 기능은 마켓에 <b>읽기 호출만</b> 한다. 등록(POST)·수정(PUT)·가격변경을 이 경로에 넣지 말 것 —
 *       가져오기는 "마켓에 이미 있는 것을 우리 쪽에 반영"하는 일이지 마켓을 바꾸는 일이 아니다.</li>
 *   <li>⚠️ 가격·재고·옵션 식별자는 <b>요청에서 받지 않는다</b>. 커밋 시 마켓을 한 번 더 조회해 서버가
 *       확정한다(미리보기~커밋 사이의 변경 반영 + 클라이언트 값 신뢰 금지).</li>
 *   <li>⚠️ 지원 플랫폼은 <b>화이트리스트</b>로 막는다. {@link ListingChannel#fetchProduct} 의 기본 구현이
 *       던지는 {@code UnsupportedOperationException} 은 전역 핸들러가 없어 그대로 두면 500 이 된다.</li>
 * </ul>
 *
 * @see ChannelAddService 채널 추가(마스터 옵션 복사 → DRAFT 셀) — 이 서비스의 형제 경로
 */
public interface CoupangListingImportService {

    /**
     * 마켓 상품을 조회해 매핑 판정 + 구성 입력 줄을 돌려준다. <b>쓰기 0회</b>, 마켓 GET 1회.
     *
     * @param masterProductId 편입 대상 마스터
     * @param request         (판매자, 플랫폼, 마켓 상품 id)
     * @return 미리보기 결과
     */
    ListingImportPreviewResponse preview(Long masterProductId, ListingImportPreviewRequest request);

    /**
     * 사용자가 채운 구성으로 채널 셀을 만든다. 셀·옵션·BOM 은 전부 성공하거나 전부 롤백된다(단일 트랜잭션).
     *
     * <p>🔴 자동생성(썸네일·상세·판매가)은 그 트랜잭션이 <b>커밋된 뒤</b> 돌고, 실패해도 셀을 되돌리지 않는다
     * ({@link MasterFromChannelService#create} 와 같은 방식). 사진이 하나도 없는 물품이면 자동생성이 400 을
     * 던지는데, 그때 셀까지 사라지면 "쿠팡 ID 를 넣었는데 아무것도 안 생긴다" 가 되기 때문이다 — 사진은 나중에
     * 채우고 [재생성] 하면 된다. 성공 여부는 응답 {@code assetsGenerated} 로 드러난다.</p>
     *
     * @param masterProductId 편입 대상 마스터
     * @param request         구성 수량 + 마스터 옵션명
     * @return 새 셀 id + 마켓 상태 + 생성된 자산 + 자동생성 성공 여부 (+ 카테고리 경고)
     */
    ChannelAddResponse importListing(Long masterProductId, ListingImportRequest request);
}
