package com.pms.service.listing;

import com.pms.dto.response.ListingOptionsResponse;

import java.util.List;

/**
 * Per-channel option selection (FEATURE_2608_06 / 42). A channel cell copies all master options; this service
 * toggles which of them are active for the channel. The active subset is what the market register/update payload
 * pushes; deactivated options keep their row (re-activation + order mapping preserved) but are excluded from the
 * payload.
 *
 * <p>⚠️ This service only flips the {@code active} flag + reports whether a re-push is needed — it never auto-pushes
 * to the market (register/update are separate explicit actions).</p>
 */
public interface ListingOptionService {

    /** Full option set (active + inactive) for the listing. Read only — {@code needsResync} is always false. */
    ListingOptionsResponse getOptions(Long listingId);

    /**
     * Set the whole active-option set for a channel listing (bulk). {@code active = activeOptionIds.contains(id)}
     * for every option. Empty set → 400; ids not belonging to the listing → 400. Returns the full option set and
     * {@code needsResync=true} if any cell of the listing is already pushed (status != DRAFT).
     *
     * <p>⚠️ On a pushed cell, unchecking an option that is registered on the market → 400: Coupang cannot delete
     * an approved option, so it would keep selling while the screen said otherwise (stop it in WING instead).
     * Turning options on is always allowed, as is undoing that before the cell is re-pushed. The judgement is
     * {@link com.pms.domain.ProductListingOption#isMarketRegistered()} — 84's master-option lock is the superset
     * that adds {@code active} on top.</p>
     */
    ListingOptionsResponse setActiveOptions(Long listingId, List<Long> activeOptionIds);
}
