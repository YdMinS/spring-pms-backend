package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Master product creation request (FEATURE_2608_06 / 3b-1).
 *
 * <p>{@code componentProductIds} defines the master's component set (must reference existing products;
 * a missing id yields 404). {@code sourceImageUrl}/{@code active} are server defaults on create
 * (null / true).</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Master product creation request")
public class MasterProductRequest {

    @NotBlank(message = "name is required")
    @Schema(description = "Master product name", example = "Galaxy S21 Bundle")
    private String name;

    @NotEmpty(message = "componentProductIds is required")
    @Schema(description = "Component product IDs (the master's product set)", example = "[3, 5]")
    private List<Long> componentProductIds;

    @Schema(description = "UI input field values (key -> value)", nullable = true)
    private Map<String, String> fieldValues;

    @Schema(description = "Default delivery (CarrierRate) ID for the price engine", nullable = true, example = "4")
    private Long defaultDeliveryId;

    @Schema(description = "Default box (Package) ID for the price engine", nullable = true, example = "5")
    private Long defaultPackageId;

    @Valid
    @Schema(description = "Options to create atomically with the master. null/empty allowed — each option "
            + "must cover the full component set (validated before any save; a violation rolls the master back).",
            nullable = true)
    private List<MasterOptionRequest> options;
}
