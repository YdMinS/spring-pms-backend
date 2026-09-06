package com.pms.service.listing;

import com.pms.dto.request.ListingMasterCreateRequest;
import com.pms.dto.response.ListingMasterCreateResponse;
import com.pms.dto.response.ListingMasterPreviewResponse;

/**
 * 마스터 미연결 셀 → 마스터 프로덕트 생성 + 연결(FEATURE_2609_22 / 04).
 *
 * <p>legacy `판매상품 등록` 으로 만들어진 셀은 마스터를 모른다(D1: {@code master_product_option_id} 가 전부
 * null). 그 셀을 쿠팡 원본과 대조한 뒤, <b>셀을 근거로</b> 마스터를 새로 만들어 붙인다 —
 * {@link CoupangListingImportService} 의 거울쌍이다(저쪽은 마스터가 있고 셀을 만든다).</p>
 *
 * <p>⚠️ 쿠팡에는 <b>읽기 호출만</b> 한다. 등록/수정/가격변경은 이 경로에 없다.</p>
 */
public interface ListingMasterCreateService {

    /**
     * 미리보기 — 저장 0회, 쿠팡 GET 1회. 확정 화면에서 실패하지 않도록 커밋의 검증을 전부 앞당겨 실행한다.
     *
     * @param listingId 마스터 미연결 셀 id
     * @return 셀 ↔ 쿠팡 대조 리포트 + 마스터명·카테고리 제안값
     * @throws com.pms.exception.ResourceNotFoundException 셀/계정 없음
     * @throws com.pms.exception.ValidationException       이미 연결됨 · 옵션/BOM 부적합 · 쿠팡 조회 실패 등
     */
    ListingMasterPreviewResponse preview(Long listingId);

    /**
     * 생성 — 마스터 생성 → 카테고리 지정 → 셀 링크 → 셀 옵션 링크 (이 순서가 규칙이다).
     *
     * <p>미리보기를 건너뛴 직접 호출을 막기 위해 {@link #preview} 의 검증을 전부 다시 실행하고, 쿠팡도 다시
     * 읽는다(클라이언트가 들고 온 값은 신뢰하지 않는다).</p>
     *
     * @param listingId 마스터 미연결 셀 id
     * @param request   마스터명 + 표준 카테고리
     * @return 생성된 마스터 id · 연결된 셀 id · 연결된 옵션 수
     */
    ListingMasterCreateResponse create(Long listingId, ListingMasterCreateRequest request);
}
