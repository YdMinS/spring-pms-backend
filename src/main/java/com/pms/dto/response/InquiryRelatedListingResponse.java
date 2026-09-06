package com.pms.dto.response;

/**
 * 문의 상세 우측 패널의 관련 상품(셀) — GET /api/inquiries/{id} (FEATURE_2609_23 / D15).
 *
 * 주문 매칭에 실패한 문의(상품문의는 주문 없는 질문이 다수라 정상이다)에도 상품 정보만은 보여주기
 * 위한 것이다. {@code external_item_id}(vendorItemId) → {@code product_listing_option.platform_option_id}
 * 로 연결한 결과이며, 연결이 없으면 null 이다.
 */
public record InquiryRelatedListingResponse(
        Long productListingId,
        String listingName,
        String optionName) {
}
