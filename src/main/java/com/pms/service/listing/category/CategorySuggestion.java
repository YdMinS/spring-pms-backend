package com.pms.service.listing.category;

/**
 * A recommended category candidate from a product name (FEATURE_2608_06 / 45), normalized (platform-agnostic).
 * Returned by {@link CategoryLookup#predict}; 0~N per call (empty = no candidate, which is normal).
 *
 * @param platformCategoryId the platform category code
 * @param name               display name of the predicted category
 * @param namePath           best-effort full name path (may equal {@code name} when no path is available)
 */
public record CategorySuggestion(String platformCategoryId, String name, String namePath) {
}
