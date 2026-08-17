package com.pms.service.listing;

import com.pms.dto.response.PendingSyncResponse;
import com.pms.dto.response.PushSyncResponse;

import java.util.List;

/**
 * Layer B of the two-layer propagation (FEATURE_2608_06 / 3d, Design 2): the gated market push. Cells marked
 * pending by layer A ({@link MasterPropagationService}) are pushed to the market on an explicit, multi-select
 * batch — never automatically. Uses the 3c {@code ListingChannel.update} seam (whole-object re-submit → PUT →
 * re-approval). No approval await (3c async principle).
 */
public interface ListingPropagationService {

    /**
     * Pending-market-sync list: cells regenerated locally (layer A) but not yet pushed. Tenant-scoped via
     * {@code @TenantId} on the derived query.
     *
     * @return the pending cells (product listing id + master name + seller + platform + status)
     */
    List<PendingSyncResponse> pendingSync();

    /**
     * Push a multi-select batch of cells to the market. Each cell: skipped if not registered
     * ({@code platformProductId == null}) / not pending ({@code needsMarketSync == false}) / has no account /
     * has no generated assets; otherwise {@code adapter.update} re-submits the whole object → cell becomes
     * {@code SUBMITTED} (re-approval, no transition guard) and its dirty marker is cleared. A per-cell failure
     * is isolated (skipped + counted), not fatal (mirrors 3c syncApprovals).
     *
     * @param listingIds the cell ids to push
     * @return counts of requested / pushed / skipped / failed
     */
    PushSyncResponse pushSync(List<Long> listingIds);
}
