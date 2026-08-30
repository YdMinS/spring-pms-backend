package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Set (or clear) the master's cover photo from the pool (FEATURE_2608_06 / 37).
 *
 * <p>⚠️ {@code imageId} is intentionally NOT {@code @NotNull} — a null <b>value</b> clears the cover photo
 * (reverts to BOM derivation). An empty request body, however, is a 400 (the field key must be present).</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Pool image ID to use as the cover photo; null value clears it")
public class MasterSourceImageRequest {

    @Schema(description = "Pool image ID (must belong to the master's pool), or null to clear", example = "5")
    private Long imageId;
}
