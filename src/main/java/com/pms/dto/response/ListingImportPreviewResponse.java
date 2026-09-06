package com.pms.dto.response;

import com.pms.domain.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 가져오기 미리보기 결과(FEATURE_2609_22 / D8): 마켓 상품의 현재 모습 + 매핑 판정 + 사용자가 채워야 할
 * 구성 입력 줄. <b>이 응답을 만드는 동안 저장은 한 번도 일어나지 않는다.</b>
 */
@Getter
@Builder
@Schema(description = "Preview of a marketplace product about to be imported as a channel cell")
public class ListingImportPreviewResponse {

    @Schema(description = "Marketplace product name", example = "노브랜드 생수 2L 6입")
    private String productName;

    @Schema(description = "Marketplace status mapped to our lifecycle status", example = "SELLING")
    private ListingStatus status;

    @Schema(description = "Marketplace leaf category code as returned by the market", example = "72882")
    private String categoryCode;

    /**
     * D14: 쿠팡의 실제 카테고리가 <b>우리가 앞으로 보낼</b> 카테고리와 같은가. 역조회 실패도, 역조회는 됐지만
     * 다른 카테고리인 경우도 똑같이 {@code false} 다(사용자가 볼 결과가 같다).
     */
    @Schema(description = "Whether the market category resolves to this master's standard category")
    private boolean categoryMatched;

    @Schema(description = "Fixed warning text shown when categoryMatched is false (D15); null otherwise")
    private String categoryWarning;

    @Schema(description = "Channel tags to be stored = market tags minus master tags (D17)")
    private List<String> channelTags;

    @Schema(description = "The master's components — one quantity input row each (D9)")
    private List<Component> components;

    @Schema(description = "Options as they exist on the marketplace")
    private List<Option> options;

    /** 마스터 구성품 한 줄(수량 입력 대상). */
    @Getter
    @Builder
    @Schema(description = "One master component (quantity input row)")
    public static class Component {

        @Schema(description = "Product ID", example = "11")
        private Long productId;

        @Schema(description = "Brand", example = "노브랜드")
        private String brand;

        @Schema(description = "Product name", example = "생수 2L")
        private String productName;
    }

    /** 마켓 옵션 하나. */
    @Getter
    @Builder
    @Schema(description = "One marketplace option")
    public static class Option {

        @Schema(description = "Option name on the marketplace", example = "6입")
        private String itemName;

        @Schema(description = "Coupang vendorItemId; null while not approved yet", example = "8123")
        private String vendorItemId;

        @Schema(description = "Coupang sellerProductItemId", example = "9123")
        private String sellerProductItemId;

        @Schema(description = "Current selling price on the marketplace", example = "12900")
        private BigDecimal salePrice;

        @Schema(description = "Stock (Coupang maximumBuyCount); null = unset", example = "50")
        private Integer stockQuantity;
    }
}
