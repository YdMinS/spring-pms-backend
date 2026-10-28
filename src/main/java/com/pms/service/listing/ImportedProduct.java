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
 * {@code fetchStatus} already reads). The rest are inferred from the register payload schema ({@code bundleInfo}:
 * from the product query docs), so a missing key must parse to {@code null} rather than throw — the service decides
 * what is fatal (a missing sale price is, a missing tag list is not).</p>
 *
 * @param productName  marketplace product name (Coupang {@code sellerProductName})
 * @param brand        2609_67: marketplace brand (Coupang {@code brand}, in the single-product response · checked in
 *                     the docs 2026-09-21) — null when absent. The [채우기] of the product registration screen uses it
 * @param categoryCode marketplace leaf category code (Coupang {@code displayCategoryCode}) — D16, display only
 * @param status       marketplace status mapped to our lifecycle status
 * @param tags         marketplace search tags (item level on Coupang); never null, empty when absent
 * @param noticeGroup  2609_45/D4-2: the notice item group ({@code notices[0].noticeCategoryName}, e.g. '가공식품').
 *                     The item group is <b>per product</b> and never differs per option — do not move it to options.
 * @param thumbnailImages representative/thumbnail image URLs ({@code items[].images[].cdnPath}), in market order.
 *                     🔴 <b>They are market-edited</b> — images with text and borders on top, not usable as product
 *                     photos as they are. <b>Never merge them</b> with {@code detailImages} (a consumer could no longer
 *                     tell the edited ones apart). never null, empty when absent
 * @param detailImages detail content image URLs ({@code items[].contents[].contentDetails[]} — for the {@code IMAGE}
 *                     type {@code content} itself is the URL, otherwise the {@code img src} inside the HTML), in the
 *                     order of the description. The photos closest to the originals are here. never null, empty when
 *                     absent
 * @param options      marketplace options; never null, empty when absent
 * @param statusName marketplace status as the market wrote it (Coupang {@code statusName}, e.g. '부분승인완료'); null
 *                     when absent
 * @param displayProductName marketplace 「노출상품명」 (Coupang {@code displayProductName}); null when absent.
 *                     🔴 {@code productName} is the 「등록상품명」 — use {@link #displayName()} for our 「노출상품명」.
 * @param marketBundleType FEATURE_2610_36 / D9 · D10: Coupang {@code bundleInfo.bundleType} ({@code SINGLE} ·
 *                     {@code AB}); null when absent or any other value
 */
public record ImportedProduct(String productName, String brand, String categoryCode, ListingStatus status,
                              List<String> tags, String noticeGroup, List<String> thumbnailImages,
                              List<String> detailImages, List<Option> options, String statusName,
                              String displayProductName, String marketBundleType) {

    /** The 11-argument form used before FEATURE_2610_36 — {@code marketBundleType = null}. */
    public ImportedProduct(String productName, String brand, String categoryCode, ListingStatus status,
                           List<String> tags, String noticeGroup, List<String> thumbnailImages,
                           List<String> detailImages, List<Option> options, String statusName,
                           String displayProductName) {
        this(productName, brand, categoryCode, status, tags, noticeGroup, thumbnailImages, detailImages,
                options, statusName, displayProductName, null);
    }

    /** 2026-10-09 이전의 10인자 형태 — {@code displayProductName = null}. */
    public ImportedProduct(String productName, String brand, String categoryCode, ListingStatus status,
                           List<String> tags, String noticeGroup, List<String> thumbnailImages,
                           List<String> detailImages, List<Option> options, String statusName) {
        this(productName, brand, categoryCode, status, tags, noticeGroup, thumbnailImages, detailImages,
                options, statusName, null);
    }

    /**
     * The name for our 노출상품명 ({@code ProductListing.name}): the market's {@code displayProductName}, or the
     * 등록상품명 when the market has none. Storing the 등록상품명 there (as import used to) swapped the two names on
     * the next push — the 등록상품명 went out as {@code displayProductName} (2026-10-09).
     */
    public String displayName() {
        return displayProductName != null && !displayProductName.isBlank() ? displayProductName : productName;
    }

    /** 2609_74 이전의 9인자 형태를 쓰는 호출부(테스트·레거시)를 위한 편의 생성자 — {@code statusName = null}. */
    public ImportedProduct(String productName, String brand, String categoryCode, ListingStatus status,
                           List<String> tags, String noticeGroup, List<String> thumbnailImages,
                           List<String> detailImages, List<Option> options) {
        this(productName, brand, categoryCode, status, tags, noticeGroup, thumbnailImages, detailImages,
                options, null);
    }

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
