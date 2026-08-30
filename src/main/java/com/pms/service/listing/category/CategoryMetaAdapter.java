package com.pms.service.listing.category;

import com.pms.domain.MarketplaceAccount;

/**
 * Per-platform category meta lookup seam (FEATURE_2608_06 / 47): resolve the required-attribute + notice
 * schema for a (platform × category). Mirrors {@link CategoryLookup} — the orchestration depends only on this
 * interface. Currently only COUPANG is real; NAVER joins later with an empty-schema placeholder.
 */
public interface CategoryMetaAdapter {

    /** Platform key (e.g. "COUPANG"). */
    String platform();

    /**
     * Resolve the meta schema for a category. <b>Returning an empty schema is allowed</b> (no required
     * attributes/notices, or a parse/empty response) — this method never throws for a missing/empty result.
     *
     * @param account      calling account (HMAC credentials)
     * @param categoryCode the platform marketplace category code
     * @return the normalized schema (possibly empty)
     */
    CategoryMetaSchema getMeta(MarketplaceAccount account, String categoryCode);
}
