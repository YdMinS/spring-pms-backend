package com.pms.service;

import com.pms.domain.Platform;
import com.pms.service.listing.category.CategoryNode;
import com.pms.service.listing.category.CategorySuggestion;

import java.util.List;

/**
 * Thin category-lookup service (FEATURE_2608_06 / 45): resolves the {@code CategoryLookup} adapter via the
 * resolver, resolves the marketplace account (seller-scoped or an arbitrary active account for the platform) when
 * that adapter requires one, and delegates. The 11st adapter needs no account (FEATURE_2610_10 / D23). The
 * controller depends only on this service (never the resolver directly).
 *
 * <p>Scope = lookup only (tree browse + product-name predict). Mapping persistence (44), meta (47) and
 * commission prefill (46) are separate.</p>
 */
public interface CategoryLookupService {

    /**
     * List the children of a category node (tree drill-down).
     *
     * @param platform   platform key (e.g. "COUPANG")
     * @param parentCode the {@code platformCategoryId} of a node returned earlier; null/blank = root
     * @param sellerId   optional — when present the (seller, platform) account is used; else any active account;
     *                   ignored when the platform's adapter needs no account
     * @return the child nodes
     */
    List<CategoryNode> browse(Platform platform, String parentCode, Long sellerId);

    /**
     * Recommend category candidates for a product name.
     *
     * @param platform    platform key (e.g. "COUPANG")
     * @param productName the product name to categorize (blank → 400)
     * @param sellerId    optional account selector (see {@link #browse})
     * @return 0~N candidates
     */
    List<CategorySuggestion> predict(Platform platform, String productName, Long sellerId);
}
