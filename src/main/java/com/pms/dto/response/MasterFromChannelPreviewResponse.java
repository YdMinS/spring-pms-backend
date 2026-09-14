package com.pms.dto.response;

import com.pms.domain.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 마켓 상품으로 마스터 만들기 — 미리보기 결과(FEATURE_2609_45 / D1~D4). 마켓 상품의 현재 모습 +
 * 카테고리 역조회 결과 + 옵션별 속성. <b>이 응답을 만드는 동안 저장은 한 번도 일어나지 않는다.</b>
 *
 * <p>⚠️ 구성상품은 여기 없다 — 마스터가 아직 없어 구성품 집합이 정해지지 않았다. 사용자가 화면에서 물품을
 * 고르고, 그 목록에 대해 옵션별 수량을 채운다(가져오기 미리보기와 다른 지점).</p>
 */
@Getter
@Builder
@Schema(description = "Preview of a marketplace product about to become a master product")
public class MasterFromChannelPreviewResponse {

    @Schema(description = "Marketplace product name", example = "노브랜드 생수 2L 6입")
    private String productName;

    @Schema(description = "Suggested master name (= productName); null when the market name is blank",
            example = "노브랜드 생수 2L 6입")
    private String suggestedMasterName;

    @Schema(description = "Marketplace status mapped to our lifecycle status", example = "SELLING")
    private ListingStatus status;

    @Schema(description = "Marketplace leaf category code as returned by the market", example = "73170")
    private String categoryCode;

    @Schema(description = "D2 reverse lookup result — our standard category id; null when unmapped",
            nullable = true, example = "3")
    private Long suggestedCategoryId;

    @Schema(description = "D2 reverse lookup result — our standard category name; null when unmapped",
            nullable = true, example = "생수")
    private String suggestedCategoryName;

    /** false 면 프론트가 표준 카테고리 선택을 <b>필수</b>로 만든다(마스터는 카테고리 없이 못 산다). */
    @Schema(description = "Whether the market category resolved to one of our standard categories")
    private boolean categoryResolved;

    @Schema(description = "Options as they exist on the marketplace")
    private List<Option> options;

    /** D4-1: 전 옵션이 같은 키·값으로 갖는 속성 = 마스터로 갈 몫. */
    @Schema(description = "Attributes every option shares (same key AND value) — stored on the master")
    private Map<String, String> commonAttributes;

    /** D4-2: 고시는 품목군 단위라 옵션에 따라 갈리지 않는다. */
    @Schema(description = "Product-info disclosure values (product-level)")
    private Map<String, String> notices;

    @Schema(description = "Notice item group (Coupang noticeCategoryName)", example = "가공식품")
    private String noticeGroup;

    /** 마켓 옵션 하나. */
    @Getter
    @Builder
    @Schema(description = "One marketplace option")
    public static class Option {

        @Schema(description = "Option name on the marketplace", example = "6입")
        private String itemName;

        @Schema(description = "Marketplace option id (Coupang vendorItemId); null while not approved yet",
                example = "8123")
        private String platformOptionId;

        @Schema(description = "Coupang sellerProductItemId", example = "9123")
        private String sellerProductItemId;

        @Schema(description = "Current selling price on the marketplace", example = "12900")
        private BigDecimal salePrice;

        @Schema(description = "Stock (Coupang maximumBuyCount); null = unset", example = "50")
        private Integer stockQuantity;

        /** D4-1: 이 옵션에서 {@code commonAttributes} 와 <b>다른 항목만</b>(= 이 옵션 고유값). */
        @Schema(description = "Attributes that differ from commonAttributes — stored on this master option")
        private Map<String, String> attributes;
    }
}
