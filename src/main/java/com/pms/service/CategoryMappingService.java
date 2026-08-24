package com.pms.service;

import com.pms.dto.request.CategoryMappingRequest;
import com.pms.dto.response.CategoryMappingResponse;

import java.util.List;

/**
 * Low-level CRUD for {@link com.pms.domain.CategoryMapping} (FEATURE_2608_06 / 44): the standard-category ×
 * platform → marketplace code table. Saving a mapping does <b>not</b> trigger lookups (B2) or commission
 * prefill (B4) — those consume the mapping later.
 */
public interface CategoryMappingService {

    /** List a standard category's mappings (one per platform). */
    List<CategoryMappingResponse> getMappings(Long categoryId);

    /** Upsert the mapping for (category, request.platform). 404 if the category is absent. */
    CategoryMappingResponse upsertMapping(Long categoryId, CategoryMappingRequest request);

    /** Delete the mapping for (category, platform). 404 if none. */
    void deleteMapping(Long categoryId, String platform);
}
