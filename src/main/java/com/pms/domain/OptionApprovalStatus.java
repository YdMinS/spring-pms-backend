package com.pms.domain;

/**
 * Approval state of a {@link ProductListingOption} on its market (FEATURE_2608_06 / 3c).
 *
 * <p><b>Binary, explicit enum — the source of truth for option approval.</b> Rejected, under-review and
 * draft options are all {@link #NOT_APPROVED} (the platform-agnostic least-common-denominator); the detailed
 * reason lives on the cell {@link ListingStatus}. Never infer approval from {@code platformOptionId}
 * (vendorItemId) nullness.</p>
 *
 * <p>New DRAFT options default to {@link #NOT_APPROVED} (entity {@code @Builder.Default}); pre-existing live
 * options are backfilled to {@link #APPROVED} (changeset 013 {@code defaultValue}). After approval,
 * {@code fetchStatus} flips the matched options to {@link #APPROVED}.</p>
 */
public enum OptionApprovalStatus {

    /** Approved on the market (selling). Backfill value for pre-existing live options. */
    APPROVED,

    /** Not yet approved — covers draft, submitted/under-review, and rejected. */
    NOT_APPROVED
}
