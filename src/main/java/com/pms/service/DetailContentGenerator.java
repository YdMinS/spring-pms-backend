package com.pms.service;

import com.pms.domain.DetailTemplate;
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

    /**
     * 템플릿을 직접 주입하는 렌더(2609_20/D4). 저장 전 미리보기 전용 — 셀의 지정값을 무시하고 이 템플릿으로 렌더한다.
     *
     * @param cell     the channel listing to build a detail page for
     * @param template 렌더에 쓸 템플릿(null 금지 — 호출부가 404 로 먼저 걸러낸다)
     * @return detail-page HTML (may be empty when the master is absent)
     */
    String generate(ProductListing cell, DetailTemplate template);
}
