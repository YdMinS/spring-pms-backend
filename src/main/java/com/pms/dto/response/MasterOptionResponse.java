package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Master option response (FEATURE_2608_06 / 3b-1). {@code items} carries product names resolved in one
 * batched {@code findAllById} (N+1 guard).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Schema(description = "Master option (SKU variant) response")
public class MasterOptionResponse {

    @Schema(description = "Option ID", example = "10")
    private Long id;

    @Schema(description = "Option name", example = "2세트")
    private String name;

    @Schema(description = "Quantity vector over the master's component products")
    private List<Item> items;

    @Schema(description = "Delivery override (CarrierRate) ID; null = master default", nullable = true)
    private Long deliveryId;

    @Schema(description = "Box override (Package) ID; null = master default", nullable = true)
    private Long packageId;

    @Schema(description = "Per-option category required-attribute override (key -> value); null = no override "
            + "(FEATURE_2608_06 / 59). Prefill for the option editor.", nullable = true)
    private Map<String, String> categoryAttributes;

    @Schema(description = "Per-option product-info disclosure override (key -> value); null = no override "
            + "(FEATURE_2608_06 / 59)", nullable = true)
    private Map<String, String> categoryNotices;

    @Schema(description = "쿠팡에 등록돼 판매 중인 옵션 — 수량·이름 수정/삭제 불가 (84). "
            + "Computed, not stored: true when a market-registered cell (platformProductId != null) holds a "
            + "same-named option that is active, has a platformOptionId, or is APPROVED.", example = "false")
    private Boolean marketRegistered;

    @Schema(description = "All-channel default stock quantity (102); null = unset (channels fall back to 9999)",
            nullable = true, example = "50")
    private Integer stockQuantity;

    @Schema(description = "How many channel options were clamped down to the new master stock by this save "
            + "(102/D5). 0 = none. Only meaningful on the update response; the clamped stock is sent to the market "
            + "with the other changed ones — see stockPushResults (FEATURE_2611_19 / D10).", example = "0")
    private int clampedChannels;

    @Schema(description = "Update response only (FEATURE_2611_19 / D28): one line per channel option whose market "
            + "stock this save changed, on a market that takes a stock push. Empty = nothing was sent; null on "
            + "every other response.", nullable = true)
    private List<StockPushResult> stockPushResults;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "What happened to one channel option's stock on its way to the market (FEATURE_2611_19 / D28)")
    public static class StockPushResult {

        @Schema(description = "Channel listing ID", example = "100")
        private Long productListingId;

        @Schema(description = "Platform of the listing", example = "LOTTEON")
        private String platform;

        @Schema(description = "Seller name of the listing", nullable = true, example = "행복상회")
        private String sellerName;

        @Schema(description = "Channel option name", example = "2세트")
        private String optionName;

        @Schema(description = "PUSHED = the market took it · SKIPPED = saved only (no market number yet) · "
                + "FAILED = the market refused, the stock stays saved", example = "PUSHED")
        private String result;

        @Schema(description = "The market's own text when FAILED, else null", nullable = true)
        private String message;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "One (product, quantity) entry of the option")
    public static class Item {

        @Schema(description = "Product ID", example = "3")
        private Long productId;

        @Schema(description = "Product name", example = "Galaxy S21")
        private String productName;

        @Schema(description = "Quantity", example = "2")
        private Integer quantity;
    }
}
