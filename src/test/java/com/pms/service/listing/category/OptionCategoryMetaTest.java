package com.pms.service.listing.category;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure merge helper (FEATURE_2608_06 / 59): master shared default {@code ++} per-option override.
 */
class OptionCategoryMetaTest {

    @Test
    void merge_optionOverridesOnlyItsKeys() {
        Map<String, String> master = Map.of("원산지", "국내산", "사이즈", "M");
        Map<String, String> option = Map.of("원산지", "수입산");

        Map<String, String> merged = OptionCategoryMeta.merge(master, option);

        assertThat(merged).containsEntry("원산지", "수입산");   // overridden
        assertThat(merged).containsEntry("사이즈", "M");        // kept from master
    }

    @Test
    void merge_nullOption_returnsMasterCopy() {
        Map<String, String> master = Map.of("원산지", "국내산");

        Map<String, String> merged = OptionCategoryMeta.merge(master, null);

        assertThat(merged).containsExactlyEntriesOf(master);
    }

    @Test
    void merge_bothNull_returnsEmpty() {
        assertThat(OptionCategoryMeta.merge(null, null)).isEmpty();
    }

    @Test
    void merge_emptyOption_keepsMaster() {
        Map<String, String> master = Map.of("원산지", "국내산");

        Map<String, String> merged = OptionCategoryMeta.merge(master, Map.of());

        assertThat(merged).containsEntry("원산지", "국내산");
    }

    @Test
    void merge_nullMaster_returnsOption() {
        Map<String, String> option = Map.of("사이즈", "L");

        Map<String, String> merged = OptionCategoryMeta.merge(null, option);

        assertThat(merged).containsExactlyEntriesOf(option);
    }
}
