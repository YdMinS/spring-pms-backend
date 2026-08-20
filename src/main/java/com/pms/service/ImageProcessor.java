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
 * the thumbnail renderer composite overlays through one path. Adding color-correction/resize ops later is
 * a new {@code type} branch, not a new class.</p>
 *
 * <p>File: {@code service/ImageProcessor.java}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageProcessor {

    private static final float JPEG_QUALITY = 0.9f;
    private static final String OVERLAY = "overlay";

    private final ImageStorageService imageStorageService;
    private final ImageCompositeSupport imageCompositeSupport;

    /**
     * Apply {@code ops} in order onto {@code baseBytes}, returning JPEG bytes. Empty/null ops → the base is
     * re-encoded unchanged. An op referencing a missing asset is skipped (the rest still apply). An
     * undecodable base surfaces as {@link IllegalArgumentException} (→400).
     */
    public byte[] process(byte[] baseBytes, List<ImageOp> ops) {
        BufferedImage decoded = decode(baseBytes, "base image");
        // Normalize to an RGB canvas (JPEG has no alpha) and paint the base as the bottom layer.
        BufferedImage canvas = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(decoded, 0, 0, canvas.getWidth(), canvas.getHeight(), null);

            if (ops != null) {
                for (ImageOp op : ops) {
                    if (OVERLAY.equalsIgnoreCase(op.getType())) {
                        applyOverlay(g, canvas.getWidth(), canvas.getHeight(), op);
                    }
                    // Unknown type → skip (forward-compatible with future op kinds).
                }
            }
        } finally {
            g.dispose();
        }
        return toJpeg(canvas);
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
        switch (anchor) {
            case "TOP_LEFT" -> { x = margin; y = margin; }
            case "TOP_RIGHT" -> { x = baseW - drawW - margin; y = margin; }
            case "BOTTOM_LEFT" -> { x = margin; y = baseH - drawH - margin; }
            case "CENTER" -> { x = (baseW - drawW) / 2; y = (baseH - drawH) / 2; }
            default -> { x = baseW - drawW - margin; y = baseH - drawH - margin; } // BOTTOM_RIGHT
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
