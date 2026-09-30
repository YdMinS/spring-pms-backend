package com.pms.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Single server-side image decode point (FEATURE_2609_81). Decodes image bytes and applies the EXIF
 * Orientation tag so phone photos stand upright, exactly as browsers show them.
 *
 * <p><b>Why.</b> Phones store the pixels sideways and write "rotate me" into EXIF Orientation.
 * {@code ImageIO.read} ignores that tag, so every server-side composite (thumbnail product photo, fixed
 * image, auto-gradient color source, detail preset base/overlay) used to come out rotated.</p>
 *
 * <p><b>Contract.</b> Orientation values 1..8 are all handled (3/6/8 = 180°/90°/270°, 2/4/5/7 = mirror
 * combinations). A missing tag, an out-of-range value or ANY metadata read failure is treated as 1
 * (pixels returned as decoded) — metadata must never fail a generation. Undecodable pixels keep the old
 * contract: {@link IllegalArgumentException} (→400).</p>
 *
 * <p>⚠️ Common component — {@code ImageProcessor} and {@code ThumbnailRenderer} delegate their
 * {@code decode} here. ❌ Do not call {@code ImageIO.read} directly for composite inputs.
 * ❌ Do not correct at upload time (stored originals stay byte-identical).
 * Out of scope: {@code service/barcode/BarcodeImageDecoder} (has its own rotation retries).</p>
 *
 * <p>Usage: {@code BufferedImage img = imageDecodeSupport.decode(bytes, "base image");}</p>
 *
 * <p>File: {@code service/ImageDecodeSupport.java}.</p>
 */
@Component
public class ImageDecodeSupport {

    /** EXIF Orientation "as stored" (no transform). */
    static final int ORIENTATION_NORMAL = 1;

    /**
     * Decode {@code bytes} and return an upright image. {@code what} names the input in the error
     * message (e.g. {@code "base image"}).
     */
    public BufferedImage decode(byte[] bytes, String what) {
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to decode " + what, e);
        }
        if (decoded == null) {
            throw new IllegalArgumentException("Unsupported/undecodable " + what + " bytes");
        }
        return applyOrientation(decoded, readOrientation(bytes));
    }

    /**
     * FEATURE_2609_81 D21·D22: before a thumbnail preset is applied, a product photo with an alpha channel is
     * painted onto white. {@code ImageProcessor} would otherwise turn transparent pixels black. No alpha →
     * the input bytes are returned as-is. With alpha → a same-size white {@code TYPE_INT_RGB} canvas with the
     * (upright, see {@link #decode}) photo drawn on it, encoded as PNG — lossless, because the caller's
     * {@code process} re-encodes to JPEG, and without an Orientation tag, so it is never rotated twice.
     *
     * <p>Call only when a preset is actually applied (no preset → keep transparency).</p>
     */
    public byte[] flattenOnWhite(byte[] bytes) {
        BufferedImage img = decode(bytes, "product image");
        if (!img.getColorModel().hasAlpha()) {
            return bytes;
        }
        BufferedImage canvas = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(canvas, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode flattened PNG", e);
        }
        return out.toByteArray();
    }

    /** EXIF Orientation 1..8; absent / out of range / unreadable → 1. */
    static int readOrientation(byte[] bytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir == null || !dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return ORIENTATION_NORMAL;
            }
            int orientation = dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            return orientation >= 1 && orientation <= 8 ? orientation : ORIENTATION_NORMAL;
        } catch (Exception e) {
            return ORIENTATION_NORMAL; // metadata must never fail the decode
        }
    }

    /**
     * Returns {@code src} itself for 1; otherwise a new upright image. 5..8 swap width and height.
     * Each matrix maps a stored pixel (x, y) to its display position (x', y').
     */
    static BufferedImage applyOrientation(BufferedImage src, int orientation) {
        if (orientation == ORIENTATION_NORMAL) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        // AffineTransform(m00, m10, m01, m11, m02, m12): x' = m00*x + m01*y + m02, y' = m10*x + m11*y + m12
        AffineTransform t = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);   // mirror horizontal
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);  // rotate 180
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);   // mirror vertical
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);    // transpose
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);   // rotate 90 clockwise
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);  // transverse
            default -> new AffineTransform(0, -1, 1, 0, 0, w);  // 8: rotate 270 clockwise
        };
        int type = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, type);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
