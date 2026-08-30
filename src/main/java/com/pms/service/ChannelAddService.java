package com.pms.service;

import com.pms.dto.request.BatchChannelAddRequest;
import com.pms.dto.request.ChannelAddRequest;
import com.pms.dto.response.BatchChannelAddResponse;
import com.pms.dto.response.ChannelAddResponse;

/**
 * Channel add (FEATURE_2608_06 / 15, Design 2): create new DRAFT {@link com.pms.domain.ProductListing}
 * cells for unregistered (seller, platform) accounts under a master product.
 *
 * <p>Copies <em>all</em> of the master's options into listing options + BOM (the master is the single option
 * universe — no subset selection), then reuses the 3b-2 {@link ListingAssetService#regenerateAssets} seam to
 * fill thumbnail/detail/per-option selling prices. Each {@code addChannel} is its own
 * {@code REQUIRES_NEW} transaction — a regenerate failure rolls only that cell back. Market push /
 * {@code platform_product_id} / approval are out of scope (3c).</p>
 */
public interface ChannelAddService {

    ChannelAddResponse addChannel(Long masterProductId, ChannelAddRequest request);

    /**
     * Register multiple unregistered accounts at once. Each target is processed independently (its own
     * {@code REQUIRES_NEW} transaction via {@link #addChannel}) so a failure isolates to that cell; the
     * response carries per-target outcomes plus aggregate counts. HTTP is always 200 (partial failure lives
     * in the body).
     */
    BatchChannelAddResponse addChannelsBatch(Long masterProductId, BatchChannelAddRequest request);
}
