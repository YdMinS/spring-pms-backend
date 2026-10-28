package com.pms.service;

import com.pms.domain.Platform;
import com.pms.dto.request.CategoryMappingRequest;
import com.pms.dto.response.CategoryMappingResponse;

import java.util.List;

/**
 * Low-level CRUD for {@link com.pms.domain.CategoryMapping} (FEATURE_2608_06 / 44): the standard-category ×
 * platform → marketplace code table. Saving a mapping does <b>not</b> trigger lookups (B2) or commission
 * prefill (B4) — those consume the mapping later. Also clears the 총 수량 memory of a Coupang category shown on the
 * mapping screen (FEATURE_2610_34 / D7 — {@link #clearGrandTotalOmitted}).
 */
public interface CategoryMappingService {

    /** List a standard category's mappings (one per platform). */
    List<CategoryMappingResponse> getMappings(Long categoryId);

    /** Upsert the mapping for (category, request.platform). 404 if the category is absent. */
    CategoryMappingResponse upsertMapping(Long categoryId, CategoryMappingRequest request);

    /** Delete the mapping for (category, platform). 404 if none. */
    void deleteMapping(Long categoryId, Platform platform);

    /**
     * FEATURE_2610_34 / D7 · D19: [기억 지우기] — new registrations of this Coupang category send 총 수량 again.
     * 404 if the tenant has no Coupang category with that code.
     */
    void clearGrandTotalOmitted(String code);
}
