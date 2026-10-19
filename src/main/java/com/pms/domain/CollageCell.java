package com.pms.domain;

/**
 * One collage cell (FEATURE_2610_21 D47) — JSON value object inside {@link CollageLayout#cells()}.
 *
 * @param productId the component product whose cover photo fills this cell (D1 · D15 — the photo itself is read
 *                  at render time, only the id is stored)
 * @param zoom      1 = the smallest size that covers the cell; lower bound = the photo fits the cell entirely
 *                  (photo-dependent, clamped at render time), upper bound 4 (D33)
 * @param cx        the photo point (0..1 of its width) shown at the cell centre — initial 0.5
 * @param cy        the photo point (0..1 of its height) shown at the cell centre — initial 0.5
 */
public record CollageCell(Long productId, Double zoom, Double cx, Double cy) {
}
