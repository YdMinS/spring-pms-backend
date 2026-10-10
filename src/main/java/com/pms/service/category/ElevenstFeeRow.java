package com.pms.service.category;

import java.util.List;

/**
 * One row of the 11st fee notice table after rowspan expansion (FEATURE_2610_10 / D27).
 *
 * @param group      1st column 「그룹군」 text
 * @param category   2nd column 「대카테고리」 text
 * @param feeText    3rd column text as shown (e.g. {@code "13%"})
 * @param feePercent the 3rd column as an integer percent; {@code null} when it is not the {@code N%} shape (D27 ④)
 * @param exclusions the 4th column 「제외 카테고리」 read by D27 ⑤; empty when none
 */
public record ElevenstFeeRow(String group, String category, String feeText, Integer feePercent,
                             List<Excluded> exclusions) {

    /**
     * One exception name of the 4th column with the percent of its comma group.
     *
     * @param name    the exception name as written in the table
     * @param percent the integer percent from the group's trailing {@code (N%)}
     */
    public record Excluded(String name, int percent) {
    }
}
