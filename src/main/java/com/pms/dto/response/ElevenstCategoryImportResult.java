package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Outcome counters of the 11st category tree import (FEATURE_2610_10 / D21 ①).
 *
 * <p>Re-import never deletes: a node gone from 11st stays (D21 ②). Leaf commission is not touched here —
 * the fee import (D22) fills it.</p>
 */
@Getter
@Builder
@Schema(description = "11st category tree import result counters")
public class ElevenstCategoryImportResult {

    /** New platform_category rows (intermediate + leaf). */
    private final int platformNodesCreated;

    /** Existing leaf rows found by code and refreshed (name / parent). */
    private final int platformNodesUpdated;

    /** Categories read from the 11st response. */
    private final int nodesProcessed;
}
