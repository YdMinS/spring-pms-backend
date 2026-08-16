package com.pms.service;

import com.pms.domain.ProductListing;

/**
 * Seam for the detail-page HTML generator (FEATURE_2608_06 / 3b-2).
 *
 * <p>{@link ListingAssetService#regenerateAssets} calls this to produce {@code detailHtml}. Step 2-2 wires
 * the real {@link TemplateDetailContentGenerator} (master fieldValues + zone images + default template →
 * {@link DetailHtmlRenderer}); the bean is swapped without changing the wiring.</p>
 */
public interface DetailContentGenerator {

    /**
     * @param cell the channel listing to build a detail page for
     * @return detail-page HTML (may be empty when the master or default template is absent)
     */
    String generate(ProductListing cell);
}
