package com.pms.dto.response;

import com.pms.service.listing.category.CategorySuggestion;

/**
 * Response for one predicted category candidate (FEATURE_2608_06 / 45) — same fields as the normalized
 * {@link CategorySuggestion}. Built via {@link #from}.
 */
public record CategorySuggestionResponse(String platformCategoryId, String name, String namePath) {

    public static CategorySuggestionResponse from(CategorySuggestion suggestion) {
        return new CategorySuggestionResponse(
                suggestion.platformCategoryId(), suggestion.name(), suggestion.namePath());
    }
}
