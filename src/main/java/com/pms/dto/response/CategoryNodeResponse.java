package com.pms.dto.response;

import com.pms.service.listing.category.CategoryNode;

/**
 * Response for one category tree child (FEATURE_2608_06 / 45) — same fields as the normalized
 * {@link CategoryNode}. Built via {@link #from}.
 */
public record CategoryNodeResponse(String platformCategoryId, String name, boolean leaf) {

    public static CategoryNodeResponse from(CategoryNode node) {
        return new CategoryNodeResponse(node.platformCategoryId(), node.name(), node.leaf());
    }
}
