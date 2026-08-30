package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Master option create/update request (FEATURE_2608_06 / 3b-1).
 *
 * <p>{@code items} must cover the master's full component set with each quantity ≥ 1 (validated in the
 * service → 400). On update, providing {@code items} replaces the whole item set; a null {@code items}
 * keeps the existing items and updates the name only.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Master option (SKU variant) create/update request")
public class MasterOptionRequest {

    @NotBlank(message = "name is required")
    @Schema(description = "Option name", example = "2세트")
    private String name;

    @Valid
    @Schema(description = "Quantity vector over the master's component products")
    private List<OptionItem> items;

    @Schema(description = "Delivery override (CarrierRate) ID; null = use master default", nullable = true)
    private Long deliveryId;

    @Schema(description = "Box override (Package) ID; null = use master default", nullable = true)
    private Long packageId;

    @Schema(description = "Per-option category required-attribute override (key -> value); null = keep on "
            + "update / no override on create (FEATURE_2608_06 / 59). Resolution = master ++ this.",
            nullable = true)
    private Map<String, String> categoryAttributes;

    @Schema(description = "Per-option product-info disclosure override (key -> value); null = keep on update / "
            + "no override on create (FEATURE_2608_06 / 59)", nullable = true)
    private Map<String, String> categoryNotices;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "One (product, quantity) entry of the option")
    public static class OptionItem {

        @NotNull(message = "productId is required")
        @Schema(description = "Component product ID", example = "3")
        private Long productId;

        @NotNull(message = "quantity is required")
        @Schema(description = "Quantity (>= 1)", example = "2")
        private Integer quantity;
    }
}
