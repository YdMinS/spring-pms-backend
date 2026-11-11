package com.pms.dto.response;

import com.pms.domain.ListingUpdateItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The compare window of a Coupang [수정 요청] (FEATURE_2611_25 / D3 · D15 · D19 · D24 · D27 · D29 · R4 · R9): the items
 * whose oclyx value differs from the product read from Coupang, the items whose oclyx value cannot be built, and the
 * option lines to pick. The texts are made by the server; the screens draw them as they are (R4).
 */
public record ListingUpdatePreviewResponse(
        @Schema(description = "Product listing id", example = "100")
        Long productListingId,
        @Schema(description = "Differing items + items whose oclyx value cannot be built, in D6 order")
        List<Line> items,
        @Schema(description = "No Coupang option carries a vendorItemId yet (D27)", example = "false")
        boolean approvalPending,
        @Schema(description = "The bundleInfo.bundleType read from Coupang is AB (D16 · D30)", example = "false")
        boolean mixedBundle,
        @Schema(description = "Option check lines shown in the window (D24 · D29)")
        List<OptionChoice> optionLines) {

    /** One compare line — one item, one check (D19). */
    public record Line(
            @Schema(description = "Item key", example = "DISPLAY_NAME")
            ListingUpdateItem key,
            @Schema(description = "Item screen name", example = "노출상품명")
            String label,
            @Schema(description = "false = the oclyx value cannot be built (D15), not checkable", example = "true")
            boolean selectable,
            @Schema(description = "Why the oclyx value cannot be built; null when selectable")
            String unavailableReason,
            @Schema(description = "Coupang value text of a product-level item; null for a per-option item",
                    example = "쿠팡 노출명")
            String coupangValue,
            @Schema(description = "oclyx value text of a product-level item; null for a per-option or unselectable item",
                    example = "셀")
            String oclyxValue,
            @Schema(description = "Differing option lines of a per-option item; empty otherwise")
            List<OptionLine> options) {
    }

    /** One paired option of a per-option item whose values differ (D19). */
    public record OptionLine(
            @Schema(description = "Product listing option id", example = "1")
            Long productListingOptionId,
            @Schema(description = "Product listing option name (oclyx)", example = "45g 1개")
            String optionName,
            @Schema(description = "Value text of the paired Coupang option", example = "8000원 · 정가 9000원")
            String coupangValue,
            @Schema(description = "Value text of the oclyx option", example = "7000원 · 정가 9000원")
            String oclyxValue) {
    }

    /** One option check line (D24 · D29 · D31). */
    public record OptionChoice(
            @Schema(description = "Product listing option id", example = "2")
            Long productListingOptionId,
            @Schema(description = "Product listing option name", example = "2세트")
            String optionName,
            @Schema(description = "Initially checked = the option is active now", example = "true")
            boolean checked) {
    }
}
