package com.pms.service;

import com.pms.domain.GeneratedProductData;
import com.pms.domain.ProductListing;
import com.pms.dto.response.GeneratedProductResponse;

/**
 * Auto-generation of a channel cell's assets (FEATURE_2608_06 / 3b-2): thumbnail, detail HTML (seam
 * stub), and per-option selling prices (margin reverse-calc).
 *
 * <p>{@link #regenerateAssets(ProductListing)} is the shared seam — first run creates the assets,
 * propagation (3d) re-runs the same code path. The endpoint-facing {@link #regenerate(Long)} /
 * {@link #getGenerated(Long)} resolve the tenant-scoped cell first (404 for a cross-tenant/absent id).</p>
 */
public interface ListingAssetService {

    /** Endpoint 4-1: regenerate + persist assets for a tenant-scoped cell (404 if absent). */
    GeneratedProductResponse regenerate(Long listingId);

    /** Endpoint 4-2: read persisted assets (404 if the cell is absent or not yet generated). */
    GeneratedProductResponse getGenerated(Long listingId);

    /**
     * Seam: (re)generate the thumbnail + detail HTML + per-option selling prices for {@code cell} and
     * upsert its {@link GeneratedProductData}. Called by {@link #regenerate(Long)} and (later) 3d
     * propagation. One transaction.
     */
    GeneratedProductData regenerateAssets(ProductListing cell);
}
