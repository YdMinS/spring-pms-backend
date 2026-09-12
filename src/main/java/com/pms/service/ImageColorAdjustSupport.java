package com.pms.service;

import com.pms.domain.ImageOp;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;

/**
 * Low-level color-correction primitive used by {@link ImageProcessor} (FEATURE_2609_35). Mirror of
 * {@link ImageCompositeSupport}: deliberately "dumb" — it applies exactly one {@code colorAdjust} op to a
 * canvas in place and owns no placement/ordering decision (the engine decides that color correction runs
 * on the base image only, before any overlay).
 *
 * <p><b>This class is the SSOT of the color formula.</b> The frontend preview mirrors it in TypeScript, so
 * the operation order below (channel gain → contrast → saturation) must not be changed independently.</p>
 *
 * <p>Formula (brightness/contrast/temperature become a per-channel 256-entry LUT; saturation cannot be a
 * LUT because luminance depends on all three channels, so it blends inside the same pass):</p>
 * <pre>
 * bGain  = 1 + brightness / 100          // multiplicative gain
 * cGain  = 1 + contrast   / 100          // contrast gain around mid-grey
 * tShift = (temperature / 100) * 0.2     // ±20% R/B gain, G untouched
 * chGain = { bGain * (1 + tShift), bGain, bGain * (1 - tShift) }
 * lut[ch][v] = clamp255(round(((v / 255) * chGain[ch] - 0.5) * cGain + 0.5) * 255)
 * lum    = 0.2126 R + 0.7152 G + 0.0722 B          // Rec.709, same as CSS saturate()
 * out    = clamp255(lum + (channel - lum) * (1 + saturation / 100))
 * </pre>
 *
 * <p>⚠️ Parameters are clamped to -100..100 here (clamping, not validation — the service layer does not
 * reject out-of-range values, matching anchor/opacity/scale).</p>
 * <p>⚠️ Precondition: the canvas must be {@code TYPE_INT_RGB} (the only caller builds it that way);
 * anything else throws {@link IllegalStateException} instead of silently misbehaving.</p>
 * <p>❌ No parallel passes, no LUT cache — a server-side one-shot pass over ~8M pixels is a few hundred ms.</p>
 *
 * <p>File: {@code service/ImageColorAdjustSupport.java}.</p>
 */
@Component
public class ImageColorAdjustSupport {

    /**
     * Apply one {@code colorAdjust} op in place on a {@code TYPE_INT_RGB} canvas. No-op when every
     * parameter is null/0 (the pass itself is skipped).
     *
     * @throws IllegalStateException when the canvas is not backed by an int raster (not {@code TYPE_INT_RGB})
     */
    public void apply(BufferedImage canvas, ImageOp op) {
        int brightness = clampParam(op.getBrightness());
        int contrast = clampParam(op.getContrast());
        int saturation = clampParam(op.getSaturation());
        int temperature = clampParam(op.getTemperature());
        if (brightness == 0 && contrast == 0 && saturation == 0 && temperature == 0) {
            return; // nothing to do → skip the whole pass
        }

        double bGain = 1.0 + brightness / 100.0;      // brightness = multiplicative gain
        double cGain = 1.0 + contrast / 100.0;        // contrast gain
        double tShift = (temperature / 100.0) * 0.2;  // temperature = ±20% R/B gain
        double[] chGain = {bGain * (1 + tShift), bGain, bGain * (1 - tShift)}; // R, G, B

        int[][] lut = new int[3][256];
        for (int ch = 0; ch < 3; ch++) {
            for (int v = 0; v < 256; v++) {
                double x = (v / 255.0) * chGain[ch];  // gain (brightness · temperature)
                x = (x - 0.5) * cGain + 0.5;          // contrast around mid-grey — order is fixed
                lut[ch][v] = clamp255((int) Math.round(x * 255.0));
            }
        }

        // Direct DataBuffer writes give up hardware acceleration for this image — irrelevant for a
        // one-shot server-side pass, and it keeps the whole correction to a single pass.
        DataBuffer db = canvas.getRaster().getDataBuffer();
        if (!(db instanceof DataBufferInt)) {
            throw new IllegalStateException("colorAdjust requires TYPE_INT_RGB canvas");
        }
        int[] px = ((DataBufferInt) db).getData();
        boolean sat = saturation != 0;
        double sGain = 1.0 + saturation / 100.0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = lut[0][(p >> 16) & 0xFF];
            int g = lut[1][(p >> 8) & 0xFF];
            int b = lut[2][p & 0xFF];
            if (sat) { // saturation is not LUT-able (luminance is a function of all 3 channels)
                double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b; // Rec.709
                r = clamp255((int) Math.round(lum + (r - lum) * sGain));
                g = clamp255((int) Math.round(lum + (g - lum) * sGain));
                b = clamp255((int) Math.round(lum + (b - lum) * sGain));
            }
            px[i] = (r << 16) | (g << 8) | b;
        }
    }

    /** Null → 0, otherwise clamped to -100..100 (clamping, not validation). */
    private static int clampParam(Integer value) {
        return value == null ? 0 : Math.max(-100, Math.min(100, value));
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
