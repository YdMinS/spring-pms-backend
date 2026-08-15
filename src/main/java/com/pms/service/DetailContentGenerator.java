package com.pms.service;

import com.pms.domain.ProductListing;

/**
 * Seam for the detail-page HTML generator (FEATURE_2608_06 / 3b-2).
 *
 * <p>{@link ListingAssetService#regenerateAssets} calls this to produce {@code detailHtml}. 3b-2 wires
 * the {@link StubDetailContentGenerator} (representative image + one line); Step 2 replaces the bean with
 * a real HTML generator without changing the wiring.</p>
 */
public interface DetailContentGenerator {

    /**
     * @param cell the channel listing to build a detail page for
     * @return detail-page HTML (non-blank)
     */
    String generate(ProductListing cell);
}
