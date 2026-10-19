package com.pms.service.collage;

/**
 * {@code thumbnail_collage.scope_key} values (FEATURE_2610_21 D38) — the only place they are built.
 *
 * <p>Usage: {@code CollageScopes.MASTER} · {@code CollageScopes.seller(7L)} → {@code SELLER:7} ·
 * {@code CollageScopes.listing(42L)} → {@code LISTING:42}.</p>
 */
public final class CollageScopes {

    /** Master default stage. */
    public static final String MASTER = "MASTER";

    private CollageScopes() {
    }

    /** Seller stage key of one seller. */
    public static String seller(Long sellerId) {
        return "SELLER:" + sellerId;
    }

    /** Listing stage key of one listing. */
    public static String listing(Long listingId) {
        return "LISTING:" + listingId;
    }
}
