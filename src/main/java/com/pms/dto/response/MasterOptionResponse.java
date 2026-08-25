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
@Builder
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
