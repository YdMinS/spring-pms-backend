package com.pms.service.collage;

import com.pms.domain.CollageCell;
import com.pms.domain.CollageLayout;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Draws one collage to PNG bytes (FEATURE_2610_21 D19 · D26 · D29 · D33 · D44 · D45 · D50).
 *
 * <ul>
 *   <li>The whole canvas is first filled with {@code gapColor}; each cell then overwrites its own rectangle, so
 *       only the gaps keep that colour (no outer border, D44).</li>
 *   <li>A cell starts transparent when {@code useTemplate} is true (the template background shows through, D19)
 *       and white when it is false (D50).</li>
 *   <li>The photo is scaled to {@code cover × zoom} (zoom clamped to [{@link CollageLayouts#minZoom}, 4]), its
 *       point ({@code cx}, {@code cy}) (clamped to 0..1) is placed at the cell centre, and it is clipped to the cell
 *       (D26 · D29). No rotation or flip (D33).</li>
 *   <li>A cell without a photo (no chosen photo and no cover photo) stays as it started (D18).</li>
 * </ul>
 *
 * <p>Usage: {@code byte[] png = collageRenderer.drawPng(layout, photosByProductId, 600, 600);}</p>
 *
 * <p>⚠️ Draws the collage only. Template, preset and JPEG encoding = {@link CollageThumbnailRenderer}.
 * File: {@code service/collage/CollageRenderer.java}.</p>
 */
@Component
public class CollageRenderer {

    /**
     * @param layout   a validated / adjusted collage
     * @param photos   decoded cell photos by product id — the chosen photo or the cover (a product without either is
     *                 absent)
     * @param width    collage width in pixels
     * @param height   collage height in pixels
     * @return PNG bytes (with alpha)
     */
    public byte[] drawPng(CollageLayout layout, Map<Long, BufferedImage> photos, int width, int height) {
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.decode(layout.gapColor()));
            g.fillRect(0, 0, width, height);
            boolean useTemplate = Boolean.TRUE.equals(layout.useTemplate());
            List<CollageLayouts.Rect> rects = CollageLayouts.cellRects(layout, width, height);
            for (int i = 0; i < rects.size(); i++) {
                CollageLayouts.Rect r = rects.get(i);
                clearCell(g, r, useTemplate);
                CollageCell cell = layout.cells().get(i);
                BufferedImage photo = photos.get(cell.productId());
                if (photo != null) {
                    drawPhoto(g, photo, r, cell);
                }
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(canvas, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode collage PNG", e);
        }
        return out.toByteArray();
    }

    private static void clearCell(Graphics2D g, CollageLayouts.Rect r, boolean transparent) {
        if (transparent) {
            Composite prev = g.getComposite();
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(r.x(), r.y(), r.w(), r.h());
            g.setComposite(prev);
        } else {
            g.setColor(Color.WHITE);
            g.fillRect(r.x(), r.y(), r.w(), r.h());
        }
    }

    private static void drawPhoto(Graphics2D g, BufferedImage photo, CollageLayouts.Rect r, CollageCell cell) {
        if (r.w() <= 0 || r.h() <= 0) {
            return;
        }
        double iw = photo.getWidth();
        double ih = photo.getHeight();
        double cover = Math.max(r.w() / iw, r.h() / ih);
        double low = CollageLayouts.minZoom(r.w(), r.h(), photo.getWidth(), photo.getHeight());
        double zoom = clamp(cell.zoom() == null ? 1.0 : cell.zoom(), low, CollageLayouts.MAX_ZOOM);
        double scale = cover * zoom;
        double dw = iw * scale;
        double dh = ih * scale;
        double cx = clamp(cell.cx() == null ? 0.5 : cell.cx(), 0, 1);
        double cy = clamp(cell.cy() == null ? 0.5 : cell.cy(), 0, 1);
        double dx = r.x() + r.w() / 2.0 - cx * dw;
        double dy = r.y() + r.h() / 2.0 - cy * dh;
        Shape prevClip = g.getClip();
        g.setClip(r.x(), r.y(), r.w(), r.h());
        g.drawImage(photo, (int) Math.round(dx), (int) Math.round(dy),
                (int) Math.round(dw), (int) Math.round(dh), null);
        g.setClip(prevClip);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
