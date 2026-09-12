package com.pms.service;

import com.pms.domain.ImageOp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * Ordered image-processing pipeline (FEATURE_2608_08). Applies a {@code List<ImageOp>} onto a base image
 * and returns JPEG bytes. Unlike {@link ThumbnailRenderer} (whose canvas is the template), here the base
 * image itself is the canvas and ops burn overlays on top of it.
 *
 * <p>The rectangle math (contain-fit, anchor, margin — all relative to the base size) lives here; the
 * actual pixel draw is delegated to the shared {@link ImageCompositeSupport} primitive, so this engine and
 * the thumbnail renderer composite overlays through one path.</p>
 *
 * <p><b>2-phase pipeline (FEATURE_2609_35).</b> Phase 1 = {@code colorAdjust} ({@link ImageColorAdjustSupport})
 * runs on the <b>base pixels only</b>, regardless of its position in the list, and at most once per preset
 * (the first {@code colorAdjust} op wins; the rest are skipped — never accumulated). Phase 2 = {@code overlay}
 * ops are drawn on top of the corrected base, so overlays are never color-corrected. <b>List order is only
 * meaningful between overlays.</b> Further op kinds are still a new {@code type} branch, not a new class.</p>
 *
 * <p>File: {@code service/ImageProcessor.java}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageProcessor {

    private static final float JPEG_QUALITY = 0.9f;
    private static final String OVERLAY = "overlay";
    /** camelCase spelling is the JSON/frontend SSOT. */
    private static final String COLOR_ADJUST = "colorAdjust";

    private final ImageStorageService imageStorageService;
    private final ImageCompositeSupport imageCompositeSupport;
    private final ImageColorAdjustSupport imageColorAdjustSupport;

    /**
     * Apply {@code ops} in order onto {@code baseBytes}, returning JPEG bytes. Empty/null ops → the base is
     * re-encoded unchanged. An op referencing a missing asset is skipped (the rest still apply). An
     * undecodable base surfaces as {@link IllegalArgumentException} (→400).
     */
    public byte[] process(byte[] baseBytes, List<ImageOp> ops) {
        BufferedImage canvas = toRgbCanvas(decode(baseBytes, "base image"));
        if (ops != null) {
            // Phase 1 — colorAdjust touches the base pixels only, always before any overlay (list order
            // is irrelevant). At most one per preset: the first one wins, the rest are skipped (no
            // accumulation) so the editor preview and the rendered image cannot diverge.
            ops.stream()
                    .filter(op -> COLOR_ADJUST.equalsIgnoreCase(op.getType()))
                    .findFirst()
                    .ifPresent(op -> imageColorAdjustSupport.apply(canvas, op));

            // Phase 2 — overlays are drawn onto the corrected base and are never color-corrected.
            Graphics2D g = canvas.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                for (ImageOp op : ops) {
                    if (OVERLAY.equalsIgnoreCase(op.getType())) {
                        applyOverlay(g, canvas.getWidth(), canvas.getHeight(), op);
                    }
                    // Unknown type → skip (forward-compatible with future op kinds).
                }
            } finally {
                g.dispose();
            }
        }
        return toJpeg(canvas);
    }

    /** Normalize to an RGB canvas (JPEG has no alpha) and paint the base as the bottom layer. */
    private BufferedImage toRgbCanvas(BufferedImage decoded) {
        BufferedImage canvas = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(decoded, 0, 0, canvas.getWidth(), canvas.getHeight(), null);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    /** Draw one overlay op onto the base canvas. A missing asset skips the op (does not fail the pipeline). */
    private void applyOverlay(Graphics2D g, int baseW, int baseH, ImageOp op) {
        byte[] overlayBytes;
        try {
            overlayBytes = imageStorageService.getBytes(op.getAssetStorageKey());
        } catch (FileNotFoundException e) {
            log.warn("Overlay asset not found, skipping op: {}", op.getAssetStorageKey());
            return;
        }
        BufferedImage overlay = decode(overlayBytes, "overlay asset");

        int shortSide = Math.min(baseW, baseH);
        int scalePercent = op.getScalePercent() == null ? 20 : Math.max(1, op.getScalePercent());
        double targetLong = shortSide * (scalePercent / 100.0);
        double fit = targetLong / Math.max(overlay.getWidth(), overlay.getHeight());
        int drawW = Math.max(1, (int) Math.round(overlay.getWidth() * fit));
        int drawH = Math.max(1, (int) Math.round(overlay.getHeight() * fit));

        int margin = (int) Math.round(shortSide * ((op.getMarginPercent() == null ? 0 : op.getMarginPercent()) / 100.0));
        String anchor = op.getAnchor() == null ? "BOTTOM_RIGHT" : op.getAnchor().toUpperCase();
        int x;
        int y;
        // 3×3 grid: three columns (left=margin, center, right) × three rows (top=margin, middle, bottom).
        int left = margin;
        int centerX = (baseW - drawW) / 2;
        int right = baseW - drawW - margin;
        int top = margin;
        int centerY = (baseH - drawH) / 2;
        int bottom = baseH - drawH - margin;
        switch (anchor) {
            case "TOP_LEFT" -> { x = left; y = top; }
            case "TOP_CENTER" -> { x = centerX; y = top; }
            case "TOP_RIGHT" -> { x = right; y = top; }
            case "CENTER_LEFT" -> { x = left; y = centerY; }
            case "CENTER" -> { x = centerX; y = centerY; }
            case "CENTER_RIGHT" -> { x = right; y = centerY; }
            case "BOTTOM_LEFT" -> { x = left; y = bottom; }
            case "BOTTOM_CENTER" -> { x = centerX; y = bottom; }
            default -> { x = right; y = bottom; } // BOTTOM_RIGHT
        }

        double opacity = op.getOpacity() == null ? 1.0 : op.getOpacity();
        imageCompositeSupport.drawOverlay(g, overlay, x, y, drawW, drawH, opacity);
    }

    private BufferedImage decode(byte[] bytes, String what) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) {
                throw new IllegalArgumentException("Unsupported/undecodable " + what + " bytes");
            }
            return img;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to decode " + what, e);
        }
    }

    /** Mirror of {@code ThumbnailRenderer.toJpeg} (quality 0.9). */
    private byte[] toJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode JPEG", e);
        } finally {
            writer.dispose();
        }
    }
}
