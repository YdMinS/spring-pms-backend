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
 * @param categoryCode marketplace leaf category code (Coupang {@code displayCategoryCode}) — D16, display only
 * @param status       marketplace status mapped to our lifecycle status
 * @param tags         marketplace search tags (item level on Coupang); never null, empty when absent
 * @param noticeGroup  2609_45/D4-2: 고시 품목군({@code notices[0].noticeCategoryName}, 예 "가공식품").
 *                     품목군은 <b>상품 단위</b>라 옵션마다 갈리지 않는다 — 옵션으로 내리지 말 것.
 * @param options      marketplace options; never null, empty when absent
 */
public record ImportedProduct(String productName, String categoryCode, ListingStatus status,
                              List<String> tags, String noticeGroup, List<Option> options) {

    /** 고시 품목군을 읽지 않는 호출부(테스트·레거시)를 위한 편의 생성자 — {@code noticeGroup = null}. */
    public ImportedProduct(String productName, String categoryCode, ListingStatus status,
                           List<String> tags, List<Option> options) {
        this(productName, categoryCode, status, tags, null, options);
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
