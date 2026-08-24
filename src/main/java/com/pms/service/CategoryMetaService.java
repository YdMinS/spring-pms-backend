package com.pms.service;

import com.pms.dto.response.CategoryMetaResponse;

import java.util.Map;

/**
 * Category meta (required attributes + product-info disclosure) lookup + master-level value storage
 * (FEATURE_2608_06 / 47). The schema comes from the per-platform adapter (empty schema allowed); the values
 * live on the master.
 */
public interface CategoryMetaService {

    /**
     * The (platform × category) meta schema plus the master's current stored values. An empty schema is a
     * normal 200.
     *
     * @param masterId master product id (tenant-scoped; 404 if absent)
     * @param platform platform key (e.g. "COUPANG")
     * @return schema + current values
     */
    CategoryMetaResponse getMeta(Long masterId, String platform);

    /**
     * Store the master-level attribute + notice values ({@code toBuilder} save). Does NOT trigger
     * regeneration (these are not thumbnail/detail binding keys). Both maps are nullable.
     *
     * @param masterId   master product id (tenant-scoped; 404 if absent)
     * @param attributes attribute values (nullable)
     * @param notices    notice values (nullable)
     */
    void updateCategoryAttributes(Long masterId, Map<String, String> attributes, Map<String, String> notices);
}
