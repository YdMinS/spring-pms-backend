package com.pms.service.listing;

import com.pms.dto.response.ListingRegisterResponse;
import com.pms.dto.response.ListingStatusResponse;
import com.pms.dto.response.ListingSyncResponse;

/**
 * Channel registration orchestration (FEATURE_2608_06 / 3c). Owns the cell state machine
 * (DRAFT→SUBMITTED→SELLING/REJECTED); the {@link ListingChannel} adapters do HTTP only.
 *
 * <p><b>⚠️ Async principle (MUST-KEEP)</b>: {@link #register} pushes and returns SUBMITTED immediately — it
 * never waits for approval. Approval (hours~days) is detected later by {@link #fetchStatus} (manual refresh)
 * or {@link #syncApprovals} (manual sweep). {@code @Scheduled} auto-polling is a follow-up (3d).</p>
 */
public interface ListingRegistrationService {

    /** Push a DRAFT cell to the market → SUBMITTED (no approval wait). */
    ListingRegisterResponse register(Long listingId);

    /** Manual refresh: fetch market status + sync option ids/approval on SELLING. */
    ListingStatusResponse fetchStatus(Long listingId);

    /** Manual sweep of pending (SUBMITTED + not-approved) listings; per-listing failures isolated. */
    ListingSyncResponse syncApprovals();
}
