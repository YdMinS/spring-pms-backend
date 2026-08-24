package com.pms.service.listing.category;

import com.pms.domain.MarketplaceAccount;

import java.util.List;

/**
 * Per-platform category lookup seam (FEATURE_2608_06 / 45) — tree drill-down + product-name prediction.
 * The lookup service depends only on this interface; {@link CoupangCategoryLookup} implements it and the NAVER
 * adapter joins later (seat only). A marketplace account is required (both calls sign with account HMAC).
 *
 * <p>Scope = lookup only. Category meta (required attributes = B3/47), commission prefill (B4/46) and mapping
 * persistence (B1/44) are out of scope.</p>
 */
public interface CategoryLookup {

    /** Platform key this adapter handles (e.g. "COUPANG"). Resolver matching key. */
    String platform();

    /**
     * List the immediate children of a category node (tree drill-down).
     *
     * @param account    the marketplace account (credentials for HMAC)
     * @param parentCode parent category code; null/blank = root
     * @return the child nodes (empty when a leaf has no children)
     */
    List<CategoryNode> browse(MarketplaceAccount account, String parentCode);

    /**
     * Recommend category candidates for a product name.
     *
     * @param account     the marketplace account (credentials for HMAC)
     * @param productName the product name to categorize
     * @return 0~N candidates (empty = no candidate; a normal, non-error result)
     */
    List<CategorySuggestion> predict(MarketplaceAccount account, String productName);
}
