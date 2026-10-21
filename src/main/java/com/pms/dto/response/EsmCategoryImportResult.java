package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Outcome counters of the ESM category tree import (FEATURE_2610_26 / D12). Re-import never deletes; a leaf found by
 * code gets its name / parent refreshed and both commissions stay.
 */
@Getter
@Builder
@Schema(description = "ESM category tree import result counters")
public class EsmCategoryImportResult {

    /** New platform_category rows (intermediate + leaf). */
    private final int platformNodesCreated;

    /** Existing leaf rows found by code and refreshed (name / parent). */
    private final int platformNodesUpdated;

    /** Categories read from the ESM responses. */
    private final int nodesProcessed;
}
