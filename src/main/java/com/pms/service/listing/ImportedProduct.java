package com.pms.service.listing;

import com.pms.domain.ListingStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One product as it currently exists on the marketplace, read back through {@link ListingChannel#fetchProduct}
 * (FEATURE_2609_22 / D8). Read-only projection — the import service turns it into a channel cell.
 *
 * <p>⚠️ Every field is nullable on purpose: only {@code statusName}/{@code items[]}/{@code itemName}/
 * {@code vendorItemId}/{@code sellerProductItemId} are confirmed against a live Coupang response (the keys
 * {@code fetchStatus} already reads). The rest are inferred from the register payload schema, so a missing
 * key must parse to {@code null} rather than throw — the service decides what is fatal (a missing sale price
 * is, a missing tag list is not).</p>
 *
 * @param productName  marketplace product name (Coupang {@code sellerProductName})
 * @param brand        2609_67: marketplace brand (Coupang {@code brand}, 단건 응답 · 문서 확인
 *                     2026-09-21) — 없으면 null. 물품 등록 화면의 [채우기] 가 이 값을 쓴다
 * @param categoryCode marketplace leaf category code (Coupang {@code displayCategoryCode}) — D16, display only
 * @param status       marketplace status mapped to our lifecycle status
 * @param tags         marketplace search tags (item level on Coupang); never null, empty when absent
 * @param noticeGroup  2609_45/D4-2: 고시 품목군({@code notices[0].noticeCategoryName}, 예 "가공식품").
 *                     품목군은 <b>상품 단위</b>라 옵션마다 갈리지 않는다 — 옵션으로 내리지 말 것.
 * @param thumbnailImages 대표/썸네일 이미지 URL({@code items[].images[].cdnPath}), 마켓 순서 그대로.
 *                     🔴 <b>마켓 가공본이다</b> — 문구·테두리가 얹힌 이미지라 제품 사진으로 그대로 쓸 수 없다.
 *                     {@code detailImages} 와 <b>절대 합치지 말 것</b>(소비자가 가공본을 구분할 수 없게 된다).
 *                     never null, empty when absent
 * @param detailImages 상세 콘텐츠 이미지 URL({@code items[].contents[].contentDetails[]} — {@code IMAGE}
 *                     타입이면 {@code content} 자체가 URL, 아니면 HTML 안의 {@code img src}), 설명 흐름 순서
 *                     그대로. 원본에 가까운 제품 사진은 여기 있다. never null, empty when absent
 * @param options      marketplace options; never null, empty when absent
 */
public record ImportedProduct(String productName, String brand, String categoryCode, ListingStatus status,
                              List<String> tags, String noticeGroup, List<String> thumbnailImages,
                              List<String> detailImages, List<Option> options) {

    /**
     * 2609_67 이전의 8인자 형태를 쓰는 호출부(테스트·레거시)를 위한 편의 생성자 — {@code brand = null}.
     * 브랜드를 읽는 곳은 물품 등록 참고 패널 하나뿐이라, 나머지 소비자를 건드리지 않는다.
     */
    public ImportedProduct(String productName, String categoryCode, ListingStatus status,
                           List<String> tags, String noticeGroup, List<String> thumbnailImages,
                           List<String> detailImages, List<Option> options) {
        this(productName, null, categoryCode, status, tags, noticeGroup, thumbnailImages, detailImages, options);
    }

    /** 이미지를 읽지 않는 호출부(테스트·레거시)를 위한 편의 생성자 — 이미지 목록 둘 다 빈 List. */
    public ImportedProduct(String productName, String categoryCode, ListingStatus status,
                           List<String> tags, String noticeGroup, List<Option> options) {
        this(productName, categoryCode, status, tags, noticeGroup, List.of(), List.of(), options);
    }

    /** 고시 품목군을 읽지 않는 호출부(테스트·레거시)를 위한 편의 생성자 — {@code noticeGroup = null}. */
    public ImportedProduct(String productName, String categoryCode, ListingStatus status,
                           List<String> tags, List<Option> options) {
        this(productName, categoryCode, status, tags, null, options);
    }

    /** 이미지 목록의 "없음"은 null 이 아니라 빈 List 다 — 호출부가 null 검사를 하지 않도록. */
    public ImportedProduct {
        thumbnailImages = thumbnailImages == null ? List.of() : thumbnailImages;
        detailImages = detailImages == null ? List.of() : detailImages;
    }

    /**
     * One marketplace option (Coupang {@code items[]} entry).
     *
     * @param itemName            option name as shown on the marketplace
     * @param vendorItemId        Coupang vendorItemId — null while the option is not approved yet (D20)
     * @param sellerProductItemId Coupang option-update id
     * @param salePrice           current selling price
     * @param originalPrice       strike-through price
     * @param stockQuantity       Coupang {@code maximumBuyCount}; null → the stock policy falls back (부록 A)
     * @param attributes          2609_45/D4: 마켓에 이미 저장된 카테고리 속성
     *                            ({@code attributeTypeName → attributeValueName}, 우리 저장 형태와 같은 Map).
     *                            🔴 <b>옵션마다 다른 값이다</b>(실측 {@code 수량 → "6개"}) — 상품 레벨로 올리지
     *                            말 것(D4-1). 빈 값은 담기지 않는다. never null, empty when absent
     * @param notices             2609_45/D4: 마켓에 이미 저장된 고시
     *                            ({@code noticeCategoryDetailName → content}). never null, empty when absent
     */
    public record Option(String itemName, String vendorItemId, String sellerProductItemId,
                         BigDecimal salePrice, BigDecimal originalPrice, Integer stockQuantity,
                         Map<String, String> attributes, Map<String, String> notices) {

        /** 속성·고시를 읽지 않는 호출부(테스트·레거시)를 위한 편의 생성자 — 둘 다 빈 Map. */
        public Option(String itemName, String vendorItemId, String sellerProductItemId,
                      BigDecimal salePrice, BigDecimal originalPrice, Integer stockQuantity) {
            this(itemName, vendorItemId, sellerProductItemId, salePrice, originalPrice, stockQuantity,
                    Map.of(), Map.of());
        }

        /** 두 Map 은 "없음"을 null 이 아니라 빈 Map 으로 표현한다 — 호출부가 null 검사를 하지 않도록. */
        public Option {
            attributes = attributes == null ? Map.of() : attributes;
            notices = notices == null ? Map.of() : notices;
        }
    }
}
