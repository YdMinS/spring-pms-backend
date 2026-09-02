package com.pms.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Query conditions for {@code GET /api/admin/master-products} (FEATURE_2608_06 / 110).
 *
 * <p><b>Extension point</b>: a new filter (status / category / channel-registered) = one field here +
 * one where clause in the repository. The controller signature never changes because the controller
 * binds this object with {@code @ModelAttribute}. From the third filter onwards, reconsider a
 * Specification-based build (separate prompt) instead of adding more finder methods.</p>
 *
 * <p>⚠️ This DTO carries <b>raw</b> request values only — no clamping, no whitelist, no defaults. The
 * front omits keys that hold their default value, so {@code sort}/{@code search} arriving as
 * {@code null} and {@code page}/{@code size} as {@code 0} is the normal path. Normalisation lives in
 * {@code MasterProductServiceImpl} (single, testable owner); do NOT give these fields initialisers.</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class MasterProductQuery {

    /** 0-indexed page. Missing/negative → 0 (service). */
    private int page;

    /** Page size. Missing (0) or negative → 25; above 100 → 100 (service). */
    private int size;

    /** {@code field,direction} e.g. {@code createdAt,desc}. Null/blank → {@code createdAt,desc} (service). */
    private String sort;

    /** Master name partial match, case-insensitive. Null/blank → no search condition (service). */
    private String search;
}
