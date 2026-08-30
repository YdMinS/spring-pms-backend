package com.pms.domain;

/**
 * Lifecycle status of a channel-cell {@link ProductListing} (FEATURE_2608_06 / 3b').
 *
 * <p>Channel-add (3b') creates a cell in {@link #DRAFT} (local only, no market id yet). Promotion to
 * {@link #SUBMITTED}/{@link #SELLING}/… is driven by the market push + sync-approvals in 3c. Existing
 * live listings are backfilled to {@link #SELLING} (changeset 012 defaultValue).</p>
 */
public enum ListingStatus {

    /** Local draft: options/BOM copied, assets generated, not yet pushed to the market. */
    DRAFT,

    /** Pushed to the market, awaiting approval (3c). */
    SUBMITTED,

    /** Live on the market. Backfill value for pre-existing listings. */
    SELLING,

    /** Market rejected the submission (3c). */
    REJECTED,

    /** Temporarily withdrawn from sale (3c). */
    SUSPENDED
}
