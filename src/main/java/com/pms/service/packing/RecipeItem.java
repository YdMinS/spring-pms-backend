package com.pms.service.packing;

/**
 * One line of what went into a box: a product and how many of it (FEATURE_2609_40 / PLAN D22).
 *
 * <p>⚠️ Deliberately carries no order, option or seller — the box memory is keyed by items only, so the
 * same goods in the same quantities map to the same box regardless of which order they came from.</p>
 *
 * @param productId product id (never null)
 * @param quantity  how many units of it went into the box (must be &gt; 0)
 */
public record RecipeItem(Long productId, Integer quantity) {
}
