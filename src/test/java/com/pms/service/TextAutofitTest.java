package com.pms.service;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the core auto-fit logic (no Graphics, FontRenderContext-based).
 * Uses a JDK logical font so results are deterministic without any bundled binary.
 */
class TextAutofitTest {

    private final Font baseFont = new Font("SansSerif", Font.PLAIN, 12);
    private final FontRenderContext frc =
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().getFontRenderContext();

    @Test
    void fit_longText_shrinksToFitRegion() {
        // A multi-word string that cannot fit at max size in a small 2-line box → must shrink.
        TextAutofit.Result result = TextAutofit.fit(
                "OKLYX Premium Product Title Sample", baseFont,
                120, 80, 60, 8, 2, frc);

        assertThat(result.fontSize()).isLessThan(60);          // shrunk below max
        assertThat(result.fontSize()).isGreaterThanOrEqualTo(8); // not below min
        assertThat(result.lines().size()).isLessThanOrEqualTo(2);
    }

    @Test
    void fit_multiLine_wrapsWithinMaxLines() {
        TextAutofit.Result result = TextAutofit.fit(
                "alpha beta gamma delta", baseFont,
                140, 400, 40, 10, 2, frc);

        assertThat(result.lines().size()).isLessThanOrEqualTo(2);
    }

    @Test
    void fit_tooLongEvenAtMin_ellipsizes() {
        // Tiny box, 1 line, long text → cannot fit even at min size → last line ellipsized.
        TextAutofit.Result result = TextAutofit.fit(
                "This is an extremely long single line that will never fit", baseFont,
                50, 20, 40, 10, 1, frc);

        assertThat(result.fontSize()).isEqualTo(10);   // fell back to min
        assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().get(0)).endsWith("…");
    }
}
