package com.pms.service;

import com.pms.service.listing.category.CategoryNode;
import com.pms.service.listing.category.CategorySuggestion;

import java.util.List;

/**
 * Thin category-lookup service (FEATURE_2608_06 / 45): resolves the marketplace account (seller-scoped or an
 * arbitrary active account for the platform) and delegates to the {@code CategoryLookup} adapter via the
 * resolver. The controller depends only on this service (never the resolver directly).
 *
 * <p>Scope = lookup only (tree browse + product-name predict). Mapping persistence (44), meta (47) and
 * commission prefill (46) are separate.</p>
 */
public interface CategoryLookupService {

    /**
     * List the children of a category node (tree drill-down).
     *
     * @param platform   platform key (e.g. "COUPANG")
     * @param parentCode parent category code; null/blank = root
     * @param sellerId   optional — when present the (seller, platform) account is used; else any active account
     * @return the child nodes
     */
    List<CategoryNode> browse(String platform, String parentCode, Long sellerId);

    /**
     * Recommend category candidates for a product name.
     *
     * @param platform    platform key (e.g. "COUPANG")
     * @param productName the product name to categorize (blank → 400)
     * @param sellerId    optional account selector (see {@link #browse})
     * @return 0~N candidates
     */
    List<CategorySuggestion> predict(String platform, String productName, Long sellerId);
}
