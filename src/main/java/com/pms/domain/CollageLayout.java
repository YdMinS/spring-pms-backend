package com.pms.domain;

import java.util.List;

/**
 * One collage (FEATURE_2610_21 D47) — JSON value object stored in {@code thumbnail_collage.layout} through
 * {@link com.pms.domain.converter.CollageLayoutConverter}. Every value is a ratio, so the same collage draws at
 * any template size (D39).
 *
 * @param shape       the shape (D30)
 * @param main        big-cell ratio 0.1..0.9 — LEFT_BIG width · TOP_BIG height · TWO_ROWS top-row height; null for
 *                    ROW / COLUMN
 * @param lines       cell-size ratios per line, each line sums to 1, one cell ≥ 0.1 (D34) — ROW [[N widths]] ·
 *                    COLUMN [[N heights]] · LEFT_BIG [[N−1 right heights]] · TOP_BIG [[N−1 bottom widths]] ·
 *                    TWO_ROWS [[⌈N/2⌉ top widths], [⌊N/2⌋ bottom widths]]
 * @param gap         gap between cells as a fraction of the collage's short side, 0..0.05 (D44)
 * @param gapColor    gap colour {@code #RRGGBB} (D51)
 * @param useTemplate true = the collage goes into the template's product-photo slot; false = the collage is the
 *                    whole thumbnail (D10 · D48)
 * @param cells       cells in cell order — ROW left→right · COLUMN top→bottom · LEFT_BIG big → right top→bottom ·
 *                    TOP_BIG big → bottom left→right · TWO_ROWS top row left→right → bottom row left→right
 */
public record CollageLayout(
        CollageShape shape,
        Double main,
        List<List<Double>> lines,
        Double gap,
        String gapColor,
        Boolean useTemplate,
        List<CollageCell> cells) {
}
