package com.pms.dto.response;

import com.pms.domain.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 판매상품 → 마스터 프로덕트 생성 미리보기(FEATURE_2609_22 / 04): 마스터 미연결 셀의 현재 모습을 쿠팡 원본과
 * 대조한 리포트. <b>이 응답을 만드는 동안 저장은 한 번도 일어나지 않는다.</b>
 *
 * <p>{@link ListingImportPreviewResponse} 의 거울쌍이다 — 저쪽은 마스터가 있고 셀을 만들고, 이쪽은 셀이 있고
 * 마스터를 만든다. 그래서 카테고리 필드가 "일치 판정 + 고정 경고문"이 아니라 "제안값"이다(D26): 비교할 마스터
 * 카테고리가 아직 없으므로 역조회 결과를 그대로 프리필로 쓰고, 실패하면 null 로 두어 사용자가 고르게 한다.</p>
 */
@Getter
@Builder
@Schema(description = "Preview of a master product about to be created from an unlinked channel cell")
public class ListingMasterPreviewResponse {

    @Schema(description = "Cell name as it is stored now", example = "노브랜드 생수 2L")
    private String listingName;

    @Schema(description = "Coupang sellerProductName", example = "노브랜드 생수 2L 6입/12입")
    private String coupangProductName;

    /**
     * D25 마스터명 기본값 = 첫 옵션 첫 구성품의 {@code {brand} {name}} 표기. ⚠️ 순서가 못박혀 있다: 옵션은
     * {@code optionName} 오름차순 첫 번째, 구성품은 그 옵션 BOM 의 {@code productId} 오름차순 첫 번째
     * ({@code RegistrationNameGenerator} 가 쓰는 정렬과 같다 — 결과가 재현 가능해야 한다).
     */
    @Schema(description = "Suggested master name (editable by the user)", example = "노브랜드 생수 2L")
    private String suggestedMasterName;

    @Schema(description = "Marketplace status mapped to our lifecycle status", example = "SELLING")
    private ListingStatus status;

    @Schema(description = "Coupang displayCategoryCode", example = "63955")
    private String categoryCode;

    /** D26: 역조회 성공 = 프리필, 실패 = null(프론트가 사용자에게 표준 카테고리를 고르게 한다). */
    @Schema(description = "Standard category id reverse-resolved from the market code; null when unresolved",
            example = "41", nullable = true)
    private Long suggestedCategoryId;

    @Schema(description = "Standard category name for the id above; null when unresolved",
            example = "생수", nullable = true)
    private String suggestedCategoryName;

    @Schema(description = "Components shared by every cell option (D35) — the master's component set")
    private List<Component> components;

    @Schema(description = "Cell options compared against the market")
    private List<OptionDiff> options;

    /** D30: 쿠팡에만 있는 옵션명. 가져오지 않는다 — 경고용이다(BOM 없는 옵션은 원가·마진이 빈다). */
    @Schema(description = "Option names that exist on Coupang only (warning; not imported)")
    private List<String> coupangOnlyOptions;

    /** 마스터가 될 구성품 한 줄. */
    @Getter
    @Builder
    @Schema(description = "One shared component of the cell options")
    public static class Component {

        @Schema(description = "Product ID", example = "7")
        private Long productId;

        @Schema(description = "Brand", example = "노브랜드")
        private String brand;

        @Schema(description = "Product name", example = "생수 2L")
        private String productName;
    }

    /** 셀 옵션 ↔ 쿠팡 옵션 대조 한 줄. */
    @Getter
    @Builder
    @Schema(description = "One cell option compared against its Coupang counterpart")
    public static class OptionDiff {

        @Schema(description = "Cell option name", example = "6입")
        private String optionName;

        @Schema(description = "Coupang itemName", example = "6입")
        private String coupangItemName;

        @Schema(description = "Cell platformOptionId (null when never mapped)", example = "8123456780",
                nullable = true)
        private String currentOptionId;

        @Schema(description = "Coupang vendorItemId", example = "8123456789", nullable = true)
        private String coupangVendorItemId;

        /** D27: 커밋 때 쿠팡 값으로 자동 교정된다(미리보기는 그 사실을 알려줄 뿐이다). */
        @Schema(description = "Whether the cell's option id differs from Coupang's (auto-fixed on create)")
        private boolean optionIdMismatch;

        @Schema(description = "Cell selling price", example = "10900")
        private BigDecimal currentPrice;

        @Schema(description = "Coupang salePrice", example = "11900", nullable = true)
        private BigDecimal coupangPrice;

        /** 보여주기만 한다 — 이 기능은 마스터를 만드는 것이지 가격을 동기화하는 것이 아니다(부록 A). */
        @Schema(description = "Whether the prices differ (display only — the cell price is kept)")
        private boolean priceMismatch;
    }
}
