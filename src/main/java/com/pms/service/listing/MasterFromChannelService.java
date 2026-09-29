package com.pms.service.listing;

import com.pms.dto.request.MasterFromChannelPreviewRequest;
import com.pms.dto.response.MasterFromChannelPreviewResponse;

/**
 * 「마켓 상품으로 시작」 — 마스터가 아직 없을 때 마켓 상품 id 하나로 옵션·카테고리·속성을 <b>읽는다</b>
 * (FEATURE_2609_45 / D1 → 2609_79).
 *
 * <p>🔁 <b>2609_79 / UX D70</b>: 저장(마스터 + 옵션 + 셀 한 번에)은 없어졌다. 「새 마스터」 는 3단 페이지
 * 저장 → {@link CoupangListingImportService#importListing} 순서로 만든다. 판매상품을 붙이는 처리는
 * {@link CoupangListingImportService} 한 곳뿐이다.</p>
 *
 * <p><b>필수 규칙</b></p>
 * <ul>
 *   <li>🔴 이름·경로는 <b>플랫폼 중립</b>이다(D17). 쿠팡은 지원 화이트리스트({@link MarketProductAccess})와
 *       사용자 대면 문구에만 남는다.</li>
 *   <li>🔴 어댑터는 {@link ListingChannelResolver} 로 얻는다(D17-1).</li>
 *   <li>⚠️ 이 기능은 마켓에 <b>읽기 호출만</b> 한다. 쓰기(마스터·셀 생성)를 이 서비스에 되살리지 말 것.</li>
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
}
