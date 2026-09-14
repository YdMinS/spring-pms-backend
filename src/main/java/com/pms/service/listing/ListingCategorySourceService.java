package com.pms.service.listing;

import com.pms.dto.response.ListingCategorySourceResponse;

/**
 * 채널 셀의 카테고리 출처 전환 (FEATURE_2609_45 / D13).
 *
 * <p>채널이 가질 수 있는 카테고리는 "가져오기로 들어온 그것" 하나뿐이라 <b>고르는 UI 를 만들지 않는다</b> —
 * 조작은 [마스터 카테고리로 변경] 토글 하나(2609_19 판매가 [기본값으로 변경]과 같은 성격)다.</p>
 *
 * <p>⚠️ 쿠팡에 아무것도 보내지 않는다. 실제 반영은 사용자가 [수정 요청]을 누를 때이며,
 * {@code needsMarketSync} 도 켜지 않는다(그 플래그는 자산 재생성 흐름의 것이라, 여기서 켜면 카테고리
 * 변경이 나가는 경로가 둘로 갈린다).</p>
 */
public interface ListingCategorySourceService {

    /**
     * @param listingId        채널 셀 id (없거나 다른 테넌트면 404)
     * @param useMasterCategory {@code true} = 채널 카테고리 코드·고시 품목군·셀 옵션 메타를 모두 비운다
     *                          (이미 비어 있으면 멱등 no-op). {@code false} = 400 — 복원할 원본이 없다.
     */
    ListingCategorySourceResponse updateCategorySource(Long listingId, boolean useMasterCategory);
}
