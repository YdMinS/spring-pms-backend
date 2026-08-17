package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Summary of a layer-A propagation (FEATURE_2608_06 / 3d, POST /api/admin/master-products/{id}/propagate).
 * Only cells that already have generated assets are propagated; a per-cell failure is isolated (counted).
 */
@Getter
@Builder
@Schema(description = "Layer-A propagate summary")
public class PropagateResponse {

    @Schema(description = "How many linked cells were regenerated + dirty-marked", example = "3")
    private int propagated;

    @Schema(description = "How many cells were skipped (no generated assets yet)", example = "1")
    private int skipped;

    @Schema(description = "How many cells failed to propagate (isolated)", example = "0")
    private int failed;
}
