package com.pms.service.listing.category;

/**
 * One child level of a marketplace category tree (FEATURE_2608_06 / 45), normalized (platform-agnostic).
 * Returned by {@link CategoryLookup#browse} so the mapping screen (F1) can drill down without hand-typing codes.
 *
 * @param platformCategoryId the platform category code (Coupang displayCategoryCode / Naver leafCategoryId)
 * @param name               display name of this category
 * @param leaf               true when this is a leaf (listable) category — no further drill-down
 */
public record CategoryNode(String platformCategoryId, String name, boolean leaf) {
}
