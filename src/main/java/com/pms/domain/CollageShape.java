package com.pms.domain;

/**
 * Collage shape (FEATURE_2610_21 D30 · D47). The name is stored in {@code thumbnail_collage.layout}, so adding a
 * shape later needs no schema change.
 *
 * <ul>
 *   <li>{@link #ROW} ① one horizontal row (N cells)</li>
 *   <li>{@link #COLUMN} ② one vertical column (N cells)</li>
 *   <li>{@link #LEFT_BIG} ③ one big cell on the left + a column of N−1 on the right (N ≥ 3)</li>
 *   <li>{@link #TOP_BIG} ④ one big cell on top + a row of N−1 below (N ≥ 3)</li>
 *   <li>{@link #TWO_ROWS} ⑤ two rows: ⌈N/2⌉ on top, ⌊N/2⌋ below (N ≥ 4)</li>
 * </ul>
 *
 * <p>Which shapes a photo count allows = {@code CollageLayouts.shapesFor(n)}.</p>
 */
public enum CollageShape {
    ROW,
    COLUMN,
    LEFT_BIG,
    TOP_BIG,
    TWO_ROWS
}
