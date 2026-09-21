package com.pms.service.listing;

import com.pms.domain.ListingStatus;

/**
 * 이름 검색이 돌려주는 마켓 상품 한 건(2609_67). 🔴 <b>사진이 없다</b> — 쿠팡 목록 응답에 이미지 필드가
 * 없기 때문이다. 사진·옵션·속성은 {@link ListingChannel#fetchProduct} 로 한 건씩 읽는다.
 *
 * @param platformProductId 마켓 상품 id (쿠팡 sellerProductId)
 * @param productName       마켓 상품명 (쿠팡 sellerProductName)
 * @param brand             브랜드 — 없으면 null
 * @param status            마켓 상태를 우리 수명주기 상태로 옮긴 값
 * @param createdAt         마켓 등록일시 문자열(그대로 노출, 파싱하지 않는다)
 */
public record ChannelProductSummary(String platformProductId, String productName, String brand,
                                    ListingStatus status, String createdAt) {}
