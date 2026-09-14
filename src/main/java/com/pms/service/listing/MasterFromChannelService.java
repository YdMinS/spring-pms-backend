package com.pms.service.listing;

import com.pms.dto.request.MasterFromChannelPreviewRequest;
import com.pms.dto.request.MasterFromChannelRequest;
import com.pms.dto.response.ListingMasterCreateResponse;
import com.pms.dto.response.MasterFromChannelPreviewResponse;

/**
 * 마켓 상품 id 하나로 <b>마스터 + 옵션 + 채널 셀</b>을 한 번에 만든다(FEATURE_2609_45 / D1).
 *
 * <p>기존 두 경로의 거울쌍이다: {@link CoupangListingImportService} 는 <b>기존 마스터</b>에 마켓 상품을 셀로
 * 붙이고, {@link ListingMasterCreateService} 는 <b>기존 미연결 셀</b>에서 마스터를 만든다. 이 서비스는 마켓
 * 상품에서 곧바로 마스터를 만드는 입구다.</p>
 *
 * <p><b>필수 규칙</b></p>
 * <ul>
 *   <li>🔴 이름·경로는 <b>플랫폼 중립</b>이다(D17). 쿠팡은 지원 화이트리스트와 사용자 대면 문구에만 남는다 —
 *       네이버가 붙는 날 화이트리스트 한 줄 말고는 바뀔 것이 없어야 한다.</li>
 *   <li>🔴 어댑터는 {@link ListingChannelResolver} 로 얻는다(D17-1). 구현체({@code CoupangListingAdapter})를
 *       직접 주입하면 seam 이 무의미해진다.</li>
 *   <li>⚠️ 이 기능은 마켓에 <b>읽기 호출만</b> 한다(미리보기·커밋 각각 GET 2회). 등록·수정·가격변경을 이
 *       경로에 넣지 말 것.</li>
 *   <li>🔴 {@code ListingAssetService} 에 <b>의존하지 않는다</b>(D5 얕은 생성). 썸네일·상세가 생기는 순간 이
 *       셀이 [마켓 반영] 대상이 되어 <b>판매중인 실물 상품이 우리 빈 상세로 덮인다</b>. 의존성을 아예 두지
 *       않는 것이 그 장치다 — "친절하게" 주입하지 말 것.</li>
 * </ul>
 */
public interface MasterFromChannelService {

    /**
     * 마켓 상품을 조회해 옵션·카테고리·속성을 돌려준다. <b>쓰기 0회</b>.
     *
     * @param request (판매자, 플랫폼, 마켓 상품 id)
     * @return 미리보기 결과
     */
    MasterFromChannelPreviewResponse preview(MasterFromChannelPreviewRequest request);

    /**
     * 사용자가 채운 구성으로 마스터·옵션·셀을 만든다. 전부 성공하거나 전부 롤백된다(단일 트랜잭션).
     *
     * @param request 마스터 이름 + 카테고리 + 구성상품 + 옵션별 수량
     * @return 새 마스터 id + 새 셀 id + 옵션 수 + 마켓 상태
     */
    ListingMasterCreateResponse create(MasterFromChannelRequest request);
}
