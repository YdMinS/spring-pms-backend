package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Summary of the pending-approval sweep (FEATURE_2608_06 / 3c, POST /api/admin/listings/sync-approvals).
 * A per-listing failure is isolated (skipped + counted), not fatal.
 */
@Getter
@Builder
@Schema(description = "sync-approvals sweep summary")
public class ListingSyncResponse {

    @Schema(description = "Number of pending listings swept", example = "5")
    private int swept;

    @Schema(description = "How many became SELLING this sweep", example = "2")
    private int promotedToSelling;

    @Schema(description = "How many are still not selling (submitted/rejected)", example = "2")
    private int stillPending;

    @Schema(description = "How many failed to sync (skipped)", example = "1")
    private int failed;
}
