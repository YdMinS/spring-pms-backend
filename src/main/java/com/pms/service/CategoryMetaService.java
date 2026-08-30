package com.pms.service;

import com.pms.dto.response.CategoryMetaResponse;
import com.pms.service.listing.category.CategoryMetaSchema;

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
     * The (platform × category) meta <b>schema</b> only (no values), keyed by a category id — so the master
     * <i>add</i> screen can fetch the schema before a master (and thus a masterId) exists (FEATURE_2608_06 / 57).
     * The schema is category-dependent, not master-dependent; the master version reuses this. An empty schema
     * is a normal 200.
     *
     * @param categoryId standard category id (400 if absent or it has no mapping for the platform)
     * @param platform   platform key (e.g. "COUPANG")
     * @return the schema (possibly empty)
     */
    CategoryMetaSchema getSchema(Long categoryId, String platform);

    /**
     * Store the master-level attribute + notice values ({@code toBuilder} save). Does NOT trigger
     * regeneration (these are not thumbnail/detail binding keys). Both maps are nullable.
     *
     * <p>{@code noticeGroup} is stored verbatim after blank-normalization ({@code ""}/whitespace →
     * {@code null} = unset, so the screen falls back to inferring). It is <b>not</b> validated against the
     * live category schema: that schema is an external response and shifts over time, and a 400 there would
     * block storing the values themselves. Always overwritten (not a partial update) — otherwise a user
     * <i>changing</i> the group could not be expressed.</p>
     *
     * @param masterId    master product id (tenant-scoped; 404 if absent)
     * @param attributes  attribute values (nullable)
     * @param notices     notice values (nullable)
     * @param noticeGroup selected notice item group (nullable/blank = unset)
     */
    void updateCategoryAttributes(Long masterId, Map<String, String> attributes, Map<String, String> notices,
                                  String noticeGroup);
}
