package com.pms.domain;

/**
 * Origin of a {@link GeneratedProductData}'s detail HTML (FEATURE_2608_06 / Step 2-2).
 *
 * <p>{@link #AUTO} = produced by the {@link com.pms.service.DetailContentGenerator}; a regeneration
 * overwrites it. {@link #MANUAL_OVERRIDE} = a user edited the raw HTML directly, so a regeneration
 * <b>preserves</b> {@code detailHtml} (thumbnail + option prices are still re-generated).</p>
 */
public enum GeneratedContentSource {
    AUTO,
    MANUAL_OVERRIDE
}
