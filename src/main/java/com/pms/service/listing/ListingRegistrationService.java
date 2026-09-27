package com.pms.service.listing;

import com.pms.dto.response.ListingRegisterResponse;
import com.pms.dto.response.ListingStatusResponse;
import com.pms.dto.response.ListingSyncResponse;
import com.pms.dto.response.MarketOptionResponse;

import java.util.List;

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

    /**
     * 108/D4: force-push an already-registered cell for re-review ([수정 요청]) — the single-cell counterpart
     * of the batch {@link ListingPropagationService#pushSync}. Unlike that batch it ignores the
     * {@code needsMarketSync} dirty marker (the user pressed the button, so channel-level edits that no
     * propagation marked dirty must go out too) and reports failures instead of silently skipping:
     * absent/cross-tenant cell → 404, not-yet-registered cell / missing account or generated assets / no
     * active option → 400.
     *
     * <p>On success (adapter update returned) the cell goes to SUBMITTED from <em>any</em> status (no
     * transition guard — REJECTED/SELLING/SUSPENDED all re-enter review), the dirty marker is cleared, a tag
     * revision is recorded, and deactivated-but-APPROVED options are reverted to NOT_APPROVED (same rule as
     * {@code pushSync}: they are dropped from the re-submitted payload).</p>
     */
    ListingRegisterResponse updateRequest(Long listingId);

    /** Manual refresh: fetch market status + sync option ids/approval on SELLING. */
    ListingStatusResponse fetchStatus(Long listingId);

    /**
     * 2609_74/D13: the options as they exist on the market right now, each marked with the channel option
     * that already holds its id (if any). One marketplace GET, zero writes.
     */
    List<MarketOptionResponse> listMarketOptions(Long listingId);

    /**
     * 2609_74/D13: attach a market option id to a channel option that has none — the manual counterpart of
     * the name match in {@link #fetchStatus}. Only an option WITHOUT an id can be linked; an id that another
     * option of the cell already holds is refused. Nothing is sent to the market.
     */
    ListingStatusResponse.OptionStatus linkMarketOption(Long listingId, Long optionId, String vendorItemId);

    /** Manual sweep of pending (SUBMITTED + not-approved) listings; per-listing failures isolated. */
    ListingSyncResponse syncApprovals();
}
