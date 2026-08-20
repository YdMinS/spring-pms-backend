package com.pms.service;

import org.springframework.stereotype.Component;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Low-level pixel-composite primitive shared by the thumbnail renderer and the detail image processor
 * (FEATURE_2608_08). Extracted from {@code ThumbnailRenderer.drawImageElement} so both engines burn
 * overlays into pixels through exactly one code path.
 *
 * <p>⚠️ Deliberately "dumb": the caller resolves the destination rectangle (contain-fit, anchor, margin).
 * This component only draws one overlay onto a {@link Graphics2D} with an opacity (SRC_OVER). Adding
 * placement logic here is out of scope — that is the engine's responsibility.</p>
 *
 * <p>File: {@code service/ImageCompositeSupport.java}.</p>
 */
@Component
public class ImageCompositeSupport {

    /**
     * Draw one overlay image onto {@code g} at a resolved pixel rect with 0..1 opacity (SRC_OVER). The
     * caller resolves the rect. Opacity is clamped to {@code [0,1]}; an opacity of 1 draws opaque.
     */
    public void drawOverlay(Graphics2D g, BufferedImage overlay, int x, int y, int w, int h, double opacity) {
        double clamped = Math.max(0.0, Math.min(1.0, opacity));
        Composite prev = g.getComposite();
        if (clamped < 1.0) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) clamped));
        }
        g.drawImage(overlay, x, y, w, h, null);
        g.setComposite(prev);
    }
}
