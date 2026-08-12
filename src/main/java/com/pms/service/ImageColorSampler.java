package com.pms.service;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * Pure pixel-sampling utility for {@link BackgroundMode#GRADIENT_AUTO} backgrounds: extracts the average
 * color of a horizontal band, and the top/bottom-half averages, from a decoded image.
 *
 * <p>No {@code Graphics2D} — reads pixels only — so it is deterministic and unit-testable in isolation
 * (see {@code ImageColorSamplerTest}), like {@link TextAutofit}. {@link ThumbnailRenderer} calls it to
 * build the auto gradient, then paints the result.</p>
 */
public final class ImageColorSampler {

    /** Fixed column step for sampling (deterministic; keeps large images cheap). */
    private static final int STEP = 4;

    private ImageColorSampler() {
    }

    /**
     * Average RGB over rows {@code [yStart, yEnd)} (all columns, fixed-step sampled). Returns
     * {@link Color#GRAY} if the range is empty.
     */
    public static Color averageColor(BufferedImage img, int yStart, int yEnd) {
        long r = 0, g = 0, b = 0, count = 0;
        int height = img.getHeight();
        int width = img.getWidth();
        int from = Math.max(0, yStart);
        int to = Math.min(height, yEnd);
        for (int y = from; y < to; y += STEP) {
            for (int x = 0; x < width; x += STEP) {
                int rgb = img.getRGB(x, y);
                r += (rgb >> 16) & 0xFF;
                g += (rgb >> 8) & 0xFF;
                b += rgb & 0xFF;
                count++;
            }
        }
        if (count == 0) {
            return Color.GRAY;
        }
        return new Color((int) (r / count), (int) (g / count), (int) (b / count));
    }

    /** {@code [0]} = average of the top half {@code [0, h/2)}, {@code [1]} = bottom half {@code [h/2, h)}. */
    public static Color[] topBottom(BufferedImage img) {
        int mid = img.getHeight() / 2;
        return new Color[]{averageColor(img, 0, mid), averageColor(img, mid, img.getHeight())};
    }
}
