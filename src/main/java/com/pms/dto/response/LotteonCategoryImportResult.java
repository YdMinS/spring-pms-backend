package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Outcome counters of the LotteON standard category import (FEATURE_2610_24 / D9 · D10).
 *
 * <p>Re-import never deletes and never touches a commission. {@code nodesSkipped} = categories whose parent is not
 * in use on LotteON ({@code use_yn = N} parents are not imported, so their children have no place).</p>
 */
@Getter
@Builder
@Schema(description = "LotteON standard category import result counters")
public class LotteonCategoryImportResult {

    private final int platformNodesCreated;

    private final int platformNodesUpdated;

    private final int nodesProcessed;

    private final int nodesSkipped;
}
