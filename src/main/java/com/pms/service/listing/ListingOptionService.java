package com.pms.service.listing;

import com.pms.dto.request.SetOptionStocksRequest;
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

    /**
     * Set the stock quantity of some options of a channel listing (FEATURE_2608_06 / 102). Unlike
     * {@link #setActiveOptions} this is a <b>partial</b> update: only the listed options are touched, because
     * stock is an independent per-option value with no "whole set" meaning.
     *
     * <p>{@code stockQuantity = null} clears the override so the option inherits the master option's stock
     * again (and 9999 if the master leaves it unset too). Empty list → 400; ids not belonging to the listing →
     * 400; a value above the option's ceiling (master stock ?? 9999) → 400, thrown before anything is saved.</p>
     *
     * <p>⚠️ Like the active-set write, this never pushes to the market — {@code needsResync} tells the front to
     * send the change with [수정 요청].</p>
     */
    ListingOptionsResponse setOptionStocks(Long listingId, List<SetOptionStocksRequest.OptionStock> stocks);
}
