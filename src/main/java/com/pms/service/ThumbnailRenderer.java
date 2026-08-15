package com.pms.service;

import com.pms.domain.BackgroundMode;
import com.pms.domain.TemplateElement;
import com.pms.domain.ThumbnailTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link ThumbnailTemplate} to a JPEG byte array using Graphics2D.
 *
 * <p>Canvas = template size. Elements are painted in array order (painter's algorithm). Text elements
 * are laid out with {@link TextAutofit} (shrink + wrap + ellipsize). Text bindings resolve from
 * {@code textBindings} (blank/absent → element skipped); image bytes come from {@code src} (storage) or
 * {@code bind} (via {@code imageBindings}). Font/image failures surface as {@link IllegalArgumentException}
 * (→400).</p>
 *
 * <p>⚠️ Common service — the single entry point for thumbnail rasterization (preview + persisted
 * generation in phase 02). Do not rasterize elsewhere. File: {@code service/ThumbnailRenderer.java}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailRenderer {

    /** Defensive upper bound on canvas dimensions (guards against absurd allocations). */
    static final int MAX_CANVAS_DIMENSION = 4000;
    private static final float JPEG_QUALITY = 0.9f;

    private final FontRegistry fontRegistry;
    private final ImageStorageService imageStorageService;

    public byte[] render(ThumbnailTemplate template,
                         Map<String, String> textBindings,
                         Map<String, byte[]> imageBindings) {
        int width = template.getCanvasWidth() == null ? 0 : template.getCanvasWidth();
        int height = template.getCanvasHeight() == null ? 0 : template.getCanvasHeight();
        if (width <= 0 || height <= 0 || width > MAX_CANVAS_DIMENSION || height > MAX_CANVAS_DIMENSION) {
            throw new IllegalArgumentException(
                    "Invalid canvas size: " + width + "x" + height + " (must be 1.." + MAX_CANVAS_DIMENSION + ")");
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            paintBackground(g, template, width, height, imageBindings);
            drawElements(g, template.getElements(), textBindings, imageBindings);
        } finally {
            g.dispose();
        }
        return toJpeg(image);
    }

    /**
     * Paints the template elements over the already-painted background.
     *
     * <p><b>Product photo = base layer.</b> The first image element bound to {@code productImage} is
     * always drawn first (bottom-most), regardless of its position in {@code elements}, so overlays
     * (fixed images, text) sit on top of the product photo. Its default region is the full canvas
     * (contain-fit: the long side meets the canvas, the margins show the background).</p>
     *
     * <p><b>z-order = array order.</b> Every other element is then painted in {@code elements} order
     * (painter's algorithm). Only the single element chosen as the base is skipped here (by identity):
     * a legacy template with two {@code productImage} elements draws the first as base and the rest in
     * array order.</p>
     *
     * <p>Package-private as a test seam — a mock {@code Graphics2D} can verify the draw ordering.</p>
     */
    void drawElements(Graphics2D g, List<TemplateElement> elements,
                      Map<String, String> textBindings, Map<String, byte[]> imageBindings) {
        if (elements == null) {
            return;
        }
        TemplateElement base = firstProductImageElement(elements);
        if (base != null) {
            drawElement(g, base, textBindings, imageBindings); // base layer, bottom-most
        }
        for (TemplateElement element : elements) {
            if (element == base) {
                continue; // already drawn as the base layer
            }
            drawElement(g, element, textBindings, imageBindings);
        }
    }

    /** Draw one element's content (image or text) then its optional border box. */
    private void drawElement(Graphics2D g, TemplateElement element,
                             Map<String, String> textBindings, Map<String, byte[]> imageBindings) {
        if ("image".equalsIgnoreCase(element.getType())) {
            drawImageElement(g, element, imageBindings);
        } else if ("text".equalsIgnoreCase(element.getType())) {
            drawTextElement(g, element, textBindings);
        }
        drawBorder(g, element);
    }

    /**
     * Draws a rectangular border around the element's region (any element type). No-op unless both a
     * valid {@code borderColor} and a positive {@code borderWidth} are set. The stroke straddles the
     * rectangle edge, so it is inset by half the width to stay inside the region.
     */
    private void drawBorder(Graphics2D g, TemplateElement element) {
        Integer width = element.getBorderWidth();
        Color color = parseColorOrNull(element.getBorderColor());
        if (color == null || width == null || width <= 0) {
            return;
        }
        TemplateElement.Region r = requireRegion(element);
        Stroke prevStroke = g.getStroke();
        Color prevColor = g.getColor();
        g.setColor(color);
        g.setStroke(new BasicStroke(width));
        int half = width / 2;
        g.drawRect(r.getX() + half, r.getY() + half,
                Math.max(0, r.getW() - width), Math.max(0, r.getH() - width));
        g.setStroke(prevStroke);
        g.setColor(prevColor);
    }

    /** First image element bound to the product photo (drawn as the base layer), or null if none. */
    static TemplateElement firstProductImageElement(List<TemplateElement> elements) {
        if (elements == null) {
            return null;
        }
        for (TemplateElement e : elements) {
            if ("image".equalsIgnoreCase(e.getType()) && PRODUCT_IMAGE_BIND.equalsIgnoreCase(e.getBind())) {
                return e;
            }
        }
        return null;
    }

    /** Binding key of the product image (source for {@link BackgroundMode#GRADIENT_AUTO}). */
    private static final String PRODUCT_IMAGE_BIND = "productImage";
    /** Neutral gray used for solid GRAY and as the gradient fallback (no product image / missing colors). */
    private static final Color GRAY = new Color(0x80, 0x80, 0x80);

    /**
     * Paints the full-canvas background layer once, before elements. Solid modes fill a color; gradient
     * modes paint a vertical {@link GradientPaint}. GRADIENT_AUTO derives its colors from the bound
     * product image's top/bottom-half averages, falling back to gray when no image is bound (e.g.
     * preview). A null mode (legacy data) is treated as WHITE.
     */
    private void paintBackground(Graphics2D g, ThumbnailTemplate template, int width, int height,
                                 Map<String, byte[]> imageBindings) {
        BackgroundMode mode = template.getBackgroundMode() == null ? BackgroundMode.WHITE : template.getBackgroundMode();
        switch (mode) {
            case BLACK -> fill(g, Color.BLACK, width, height);
            case GRAY -> fill(g, GRAY, width, height);
            case GRADIENT_MANUAL -> {
                Color top = parseColorOrNull(template.getGradientTopColor());
                Color bottom = parseColorOrNull(template.getGradientBottomColor());
                if (top == null || bottom == null) {
                    fill(g, GRAY, width, height); // missing color(s) → gray fallback
                } else {
                    fillGradient(g, top, bottom, width, height);
                }
            }
            case GRADIENT_AUTO -> {
                byte[] productBytes = imageBindings == null ? null : imageBindings.get(PRODUCT_IMAGE_BIND);
                if (productBytes == null) {
                    fill(g, GRAY, width, height); // no product image (preview) → gray fallback
                } else {
                    Color[] tb = ImageColorSampler.topBottom(decode(productBytes, "auto-gradient source"));
                    fillGradient(g, tb[0], tb[1], width, height);
                }
            }
            default -> fill(g, Color.WHITE, width, height); // WHITE (and null-normalized)
        }
    }

    private static void fill(Graphics2D g, Color color, int width, int height) {
        g.setColor(color);
        g.fillRect(0, 0, width, height);
    }

    private static void fillGradient(Graphics2D g, Color top, Color bottom, int width, int height) {
        g.setPaint(new GradientPaint(0, 0, top, 0, height, bottom));
        g.fillRect(0, 0, width, height);
    }

    /** {@code #RRGGBB} → Color; null/blank → null (caller decides fallback). Invalid format → 400. */
    private static Color parseColorOrNull(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        return parseColor(hex);
    }

    private void drawImageElement(Graphics2D g, TemplateElement element, Map<String, byte[]> imageBindings) {
        byte[] bytes = null;
        if (element.getSrc() != null && !element.getSrc().isBlank()) {
            bytes = loadStored(element.getSrc());
        } else if (element.getBind() != null && imageBindings != null) {
            bytes = imageBindings.get(element.getBind());
        }
        if (bytes == null) {
            return; // conditional render: no source → skip
        }
        BufferedImage src = decode(bytes, "image element");
        TemplateElement.Region r = requireRegion(element);

        // contain: keep aspect ratio, fit within region.
        double scale = Math.min((double) r.getW() / src.getWidth(), (double) r.getH() / src.getHeight());
        int drawW = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int drawH = Math.max(1, (int) Math.round(src.getHeight() * scale));
        int x = r.getX() + (r.getW() - drawW) / 2;
        int y = r.getY() + (r.getH() - drawH) / 2;

        double opacity = element.getOpacity() == null ? 1.0 : clamp01(element.getOpacity());
        Composite previous = g.getComposite();
        if (opacity < 1.0) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) opacity));
        }
        g.drawImage(src, x, y, drawW, drawH, null);
        g.setComposite(previous);
    }

    private void drawTextElement(Graphics2D g, TemplateElement element, Map<String, String> textBindings) {
        String value = textBindings == null ? null : textBindings.get(element.getBind());
        if (value == null || value.isBlank()) {
            return; // conditional render: blank binding → skip
        }
        TemplateElement.Region r = requireRegion(element);
        if (element.getMaxFontSize() == null || element.getMinFontSize() == null) {
            throw new IllegalArgumentException("Text element requires maxFontSize and minFontSize");
        }
        Font base = fontRegistry.load(element.getFontId());

        int padTop = pad(element, TemplateElement.Padding::getTop);
        int padBottom = pad(element, TemplateElement.Padding::getBottom);
        int padLeft = pad(element, TemplateElement.Padding::getLeft);
        int padRight = pad(element, TemplateElement.Padding::getRight);
        int availW = Math.max(1, r.getW() - padLeft - padRight);
        int availH = Math.max(1, r.getH() - padTop - padBottom);

        int maxLines = element.getMaxLines() == null ? 1 : Math.max(1, element.getMaxLines());
        double lineSpacing = element.getLineSpacing() == null ? 1.0 : element.getLineSpacing();
        TextAutofit.Result fit = TextAutofit.fit(value, base, availW, availH,
                element.getMaxFontSize(), element.getMinFontSize(), maxLines, lineSpacing,
                g.getFontRenderContext());

        g.setFont(base.deriveFont((float) fit.fontSize()));
        Color fillColor = parseColor(element.getColor());
        Color gradientEnd = parseColorOrNull(element.getGradientColor()); // null → solid fillColor

        // Optional glyph outline (stroke behind the fill) for legibility over images.
        Color outlineColor = parseColorOrNull(element.getOutlineColor());
        Integer outlineWidth = element.getOutlineWidth();
        boolean hasOutline = outlineColor != null && outlineWidth != null && outlineWidth > 0;

        String hAlign = alignH(element);
        String vAlign = alignV(element);
        int blockHeight = fit.lines().size() * fit.lineHeight();
        int blockTop = switch (vAlign) {
            case "center" -> r.getY() + padTop + (availH - blockHeight) / 2;
            case "bottom" -> r.getY() + padTop + (availH - blockHeight);
            default -> r.getY() + padTop; // top
        };

        // Solid color, or a directional gradient spanning the whole text block box.
        Paint fillPaint = gradientEnd == null
                ? fillColor
                : gradientPaint(fillColor, gradientEnd, element.getGradientAngle(),
                        r.getX() + padLeft, blockTop, availW, Math.max(1, blockHeight));

        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < fit.lines().size(); i++) {
            String line = fit.lines().get(i);
            int lineWidth = fm.stringWidth(line);
            int x = switch (hAlign) {
                case "center" -> r.getX() + padLeft + (availW - lineWidth) / 2;
                case "right" -> r.getX() + padLeft + (availW - lineWidth);
                default -> r.getX() + padLeft; // left
            };
            int baseline = blockTop + fit.ascent() + i * fit.lineHeight();
            if (hasOutline && !line.isEmpty()) {
                drawTextOutline(g, line, x, baseline, outlineColor, outlineWidth);
            }
            g.setPaint(fillPaint);
            g.drawString(line, x, baseline);
        }
    }

    /**
     * Strokes the outline of one text line behind its fill. The glyph path is stroked with 2×width and
     * the solid fill (drawn afterwards) covers the inner half, so the visible outer band ≈ {@code width}.
     */
    private void drawTextOutline(Graphics2D g, String line, int x, int baseline, Color color, int width) {
        TextLayout layout = new TextLayout(line, g.getFont(), g.getFontRenderContext());
        Shape outline = layout.getOutline(AffineTransform.getTranslateInstance(x, baseline));
        Stroke prevStroke = g.getStroke();
        Color prevColor = g.getColor();
        g.setColor(color);
        g.setStroke(new BasicStroke(width * 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(outline);
        g.setStroke(prevStroke);
        g.setColor(prevColor);
    }

    /**
     * Linear gradient across a box in the requested direction. Angle is degrees clockwise from
     * top→bottom (0 = top→bottom, 90 = left→right, 180 = bottom→top); null → 0. Start/end are the box's
     * extreme projections onto the direction, so {@code startColor} is at the leading edge.
     */
    private static GradientPaint gradientPaint(Color startColor, Color endColor, Integer angleDeg,
                                               int boxX, int boxY, int boxW, int boxH) {
        double rad = Math.toRadians(angleDeg == null ? 0 : angleDeg);
        double dx = Math.sin(rad); // 0°→(0,1) down, 90°→(1,0) right
        double dy = Math.cos(rad);
        double cx = boxX + boxW / 2.0;
        double cy = boxY + boxH / 2.0;
        // Half-extent of the box projected onto the direction (max corner projection).
        double proj = Math.abs(boxW / 2.0 * dx) + Math.abs(boxH / 2.0 * dy);
        if (proj < 0.5) {
            proj = 0.5; // guard: GradientPaint rejects coincident start/end points
        }
        return new GradientPaint(
                (float) (cx - proj * dx), (float) (cy - proj * dy), startColor,
                (float) (cx + proj * dx), (float) (cy + proj * dy), endColor);
    }

    private byte[] loadStored(String storageKey) {
        try {
            return imageStorageService.getBytes(storageKey);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to load image asset: " + storageKey, e);
        }
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

    private static TemplateElement.Region requireRegion(TemplateElement element) {
        TemplateElement.Region r = element.getRegion();
        if (r == null || r.getX() == null || r.getY() == null || r.getW() == null || r.getH() == null) {
            throw new IllegalArgumentException("Element requires a complete region {x,y,w,h}");
        }
        return r;
    }

    private static int pad(TemplateElement element, java.util.function.Function<TemplateElement.Padding, Integer> get) {
        TemplateElement.Padding p = element.getPadding();
        if (p == null) {
            return 0;
        }
        Integer v = get.apply(p);
        return v == null ? 0 : v;
    }

    private static String alignH(TemplateElement element) {
        return element.getAlign() == null || element.getAlign().getH() == null
                ? "left" : element.getAlign().getH();
    }

    private static String alignV(TemplateElement element) {
        return element.getAlign() == null || element.getAlign().getV() == null
                ? "top" : element.getAlign().getV();
    }

    private static Color parseColor(String hex) {
        if (hex == null || hex.isBlank()) {
            return Color.BLACK;
        }
        try {
            return Color.decode(hex);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid color: " + hex);
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
