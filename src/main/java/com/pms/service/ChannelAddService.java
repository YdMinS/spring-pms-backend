package com.pms.service;

import com.pms.dto.request.ChannelAddRequest;
import com.pms.dto.response.ChannelAddResponse;

/**
 * Channel add (FEATURE_2608_06 / 3b', Design 2): create a new DRAFT {@link com.pms.domain.ProductListing}
 * cell for an unregistered (seller, platform) account under a master product.
 *
 * <p>Copies the selected subset of the master's options into listing options + BOM, then reuses the 3b-2
 * {@link ListingAssetService#regenerateAssets} seam to fill thumbnail/detail/per-option selling prices.
 * The whole flow is one transaction — a regenerate failure rolls the copy back. Market push /
 * {@code platform_product_id} / approval are out of scope (3c).</p>
 */
public interface ChannelAddService {

    ChannelAddResponse addChannel(Long masterProductId, ChannelAddRequest request);
}
