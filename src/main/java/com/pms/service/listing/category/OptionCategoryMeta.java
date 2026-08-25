package com.pms.service.listing.category;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single owner of the master + option category-meta merge (FEATURE_2608_06 / 59). The master carries the
 * shared default values; an option overrides only the keys it provides.
 *
 * <p>Platform-agnostic on purpose: the adapter's per-item assembly and the register validation both go through
 * this helper (do <b>not</b> duplicate the merge logic). The NAVER adapter will reuse the same {@code merge}
 * when it assembles per-item attributes.</p>
 */
public final class OptionCategoryMeta {

    private OptionCategoryMeta() {
    }

    /**
     * {@code resolved = master ++ option} — a copy of the master values with the option's non-null entries put
     * on top (each key in {@code option} overrides the master). Either argument may be null (null = absent);
     * both null → empty map.
     *
     * @param master the shared default values (nullable)
     * @param option the per-option override values (nullable)
     * @return the merged view (never null)
     */
    public static Map<String, String> merge(Map<String, String> master, Map<String, String> option) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (master != null) {
            resolved.putAll(master);
        }
        if (option != null) {
            resolved.putAll(option);
        }
        return resolved;
    }
}
