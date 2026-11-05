package com.pms.domain;

/**
 * Origin of a {@link GeneratedProductData}'s detail HTML (FEATURE_2608_06 / Step 2-2).
 *
 * <p>{@link #AUTO} = produced by the {@link com.pms.service.DetailContentGenerator}; a regeneration
 * overwrites it. {@link #MANUAL_OVERRIDE} = a user edited the raw HTML directly, so a regeneration
 * <b>preserves</b> {@code detailHtml} (thumbnail + option prices are still re-generated).</p>
 *
 * <p>{@link #MARKET} (FEATURE_2611_11 / D7 · D23) = the value was taken from the marketplace (import or
 * [「마켓 기준으로 맞추기」]); a regeneration preserves it like {@link #MANUAL_OVERRIDE}. Used by the thumbnail and the
 * detail HTML only — a price or an option name never carries it.</p>
 */
public enum GeneratedContentSource {
    AUTO,
    MANUAL_OVERRIDE,
    MARKET
}
