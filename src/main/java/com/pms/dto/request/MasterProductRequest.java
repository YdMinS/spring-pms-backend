package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "Mall-shared detail-page source", nullable = true)
    private String detailSource;

    @Schema(description = "UI input field values (key -> value)", nullable = true)
    private Map<String, String> fieldValues;
}
