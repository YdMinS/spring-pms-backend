package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Create/rename payload for a {@link com.pms.domain.DetailImageGroup} (FEATURE_2609_03).
 *
 * <p>Only the display name is settable: {@code code} is derived on create and immutable afterwards, and
 * {@code sortOrder} is creation order (no reorder endpoint). No {@code @Setter} (Builder only).</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Detail image group create/rename request")
public class DetailImageGroupRequest {

    @NotBlank
    @Size(max = 255)
    @Schema(description = "Display name", example = "제품 사진")
    private String name;
}
