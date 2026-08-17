package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Master product content-patch request (FEATURE_2608_06 / 3b-1). PATCH semantics: a null field keeps the
 * existing value.
 *
 * <p>{@code active} toggles soft-delete (false) / restore (true). Changing {@code componentProductIds}
 * re-validates every existing option against the new component set in the same transaction (a mismatch
 * rolls the whole patch back → 400).</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Master product content-patch request (null field = keep existing)")
public class MasterProductUpdateRequest {

    @Schema(description = "Master product name", nullable = true)
    private String name;

    @Schema(description = "Mall-shared detail-page source", nullable = true)
    private String detailSource;

    @Schema(description = "UI input field values (key -> value)", nullable = true)
    private Map<String, String> fieldValues;

    @Schema(description = "Activation flag (false = soft delete, true = restore)", nullable = true)
    private Boolean active;

    @Schema(description = "New component product IDs; re-validates existing options", nullable = true)
    private List<Long> componentProductIds;

    @Schema(description = "Default delivery (CarrierRate) ID (null = keep existing)", nullable = true)
    private Long defaultDeliveryId;

    @Schema(description = "Default box (Package) ID (null = keep existing)", nullable = true)
    private Long defaultPackageId;
}
