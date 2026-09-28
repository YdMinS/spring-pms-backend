package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Create/rename payload for a {@link com.pms.domain.PurchasePlace} (FEATURE_2609_76). Only the name is
 * settable — {@code sortOrder} is creation order. The service trims it before the duplicate check (D15).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Purchase place create/rename request")
public class PurchasePlaceRequest {

    @NotBlank
    @Size(max = 255)
    @Schema(description = "Display name", example = "이마트")
    private String name;
}
