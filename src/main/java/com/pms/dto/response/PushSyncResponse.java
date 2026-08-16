package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Summary of a layer-B batch push (FEATURE_2608_06 / 3d, POST /api/admin/listings/push-sync). Each pushed
 * cell is re-submitted whole (→ SUBMITTED, re-approval) and its dirty marker cleared; a per-cell failure is
 * isolated (counted). Not-yet-registered / not-pending cells are skipped.
 */
@Getter
@Builder
@Schema(description = "push-sync batch summary")
public class PushSyncResponse {

    @Schema(description = "How many listing ids were requested", example = "5")
    private int requested;

    @Schema(description = "How many were pushed to the market successfully", example = "3")
    private int pushed;

    @Schema(description = "How many were skipped (not registered / not pending / no account / no assets)", example = "1")
    private int skipped;

    @Schema(description = "How many failed to push (isolated)", example = "1")
    private int failed;
}
