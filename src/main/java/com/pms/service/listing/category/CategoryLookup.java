package com.pms.service.listing.category;

import com.pms.domain.Platform;
import com.pms.domain.MarketplaceAccount;

import java.util.List;

/**
 * Per-platform category lookup seam (FEATURE_2608_06 / 45) — tree drill-down + product-name prediction.
 * The lookup service depends only on this interface; {@link CoupangCategoryLookup} (live calls signed with the
 * account HMAC) and {@link ElevenstCategoryLookup} (the imported list, no account — FEATURE_2610_10 / D23)
 * implement it; the NAVER adapter joins later (seat only). {@link #requiresAccount()} tells the service whether
 * to resolve a marketplace account first.
 *
 * <p>Scope = lookup only. Category meta (required attributes = B3/47), commission prefill (B4/46) and mapping
 * persistence (B1/44) are out of scope.</p>
 */
public interface CategoryLookup {

    /** Platform key this adapter handles (e.g. {@link Platform#COUPANG}). Resolver matching key. */
    Platform platform();

    /**
     * Whether {@link #browse} / {@link #predict} need a marketplace account. When {@code false} the service passes
     * {@code null} as the account.
     */
    default boolean requiresAccount() {
        return true;
    }

    /**
     * List the immediate children of a category node (tree drill-down).
     *
     * @param account    the marketplace account (credentials for HMAC); {@code null} when
     *                   {@link #requiresAccount()} is {@code false}
     * @param parentCode the {@code platformCategoryId} of a node this adapter returned; null/blank = root
     * @return the child nodes (empty when a leaf has no children)
     */
    List<CategoryNode> browse(MarketplaceAccount account, String parentCode);

    /**
     * Recommend category candidates for a product name.
     *
     * @param account     the marketplace account (credentials for HMAC); {@code null} when
     *                    {@link #requiresAccount()} is {@code false}
     * @param productName the product name to categorize
     * @return 0~N candidates (empty = no candidate; a normal, non-error result)
     */
    List<CategorySuggestion> predict(MarketplaceAccount account, String productName);
}
