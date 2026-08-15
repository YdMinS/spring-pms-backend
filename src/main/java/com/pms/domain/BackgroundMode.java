package com.pms.domain;

/**
 * How a {@link ThumbnailTemplate}'s background layer is painted (once, before elements) by
 * {@link com.pms.service.ThumbnailRenderer} (FEATURE_2608_05 Step 07).
 *
 * <ul>
 *   <li>{@link #WHITE}/{@link #BLACK}/{@link #GRAY} — solid fill.</li>
 *   <li>{@link #GRADIENT_AUTO} — vertical gradient from the product image's top/bottom half average
 *       colors; falls back to gray when no product image is bound (e.g. preview).</li>
 *   <li>{@link #GRADIENT_MANUAL} — vertical gradient between two explicit {@code #RRGGBB} colors.</li>
 * </ul>
 *
 * <p>An IMAGE (uploaded full-canvas background) mode is deliberately out of scope here; it can be added
 * non-destructively later (enum value + re-added storage-key column).</p>
 */
public enum BackgroundMode {
    WHITE,
    BLACK,
    GRAY,
    GRADIENT_AUTO,
    GRADIENT_MANUAL
}
