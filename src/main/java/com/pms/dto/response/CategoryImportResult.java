package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Outcome counters of a Coupang category xlsx import (FEATURE_2608_06 / 53).
 *
 * <p>Idempotent: re-import updates PlatformCategory nodes (fee/name) and adds only new oclyx mirror leaves +
 * mappings; existing oclyx curation is preserved (counted under {@code skipped}).</p>
 */
@Getter
@Builder
@Schema(description = "Coupang category import result counters")
public class CategoryImportResult {

    /** New PlatformCategory nodes inserted (intermediate + leaf). */
    private final int platformNodesCreated;

    /** Existing PlatformCategory leaf nodes updated (name / commission / parent). */
    private final int platformNodesUpdated;

    /** New oclyx mirror Category nodes inserted (intermediate + leaf). */
    private final int oclyxNodesCreated;

    /** New CategoryMapping (oclyx leaf ↔ PlatformCategory) rows inserted. */
    private final int mappingsCreated;

    /** Total leaf rows processed after dedup. */
    private final int leavesProcessed;

    /** Leaves whose oclyx mirror + mapping already existed (skipped to preserve curation). */
    private final int skipped;
}
