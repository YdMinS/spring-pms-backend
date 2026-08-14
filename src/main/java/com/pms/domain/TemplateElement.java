package com.pms.domain;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A single element of a {@link ThumbnailTemplate} (JSON value object, NOT a JPA entity).
 *
 * <p>Persisted as part of {@code ThumbnailTemplate.elements} via
 * {@link com.pms.domain.converter.TemplateElementListConverter} (JSON TEXT column). The element array
 * is the only extension seam of the thumbnail model — new element kinds are added as data, never as
 * schema changes (see FEATURE_2608_05 PLAN §3/§6).</p>
 *
 * <p><b>type</b> = {@code text} | {@code image}. Text elements bind an input value (brand/product name)
 * and are auto-fitted (font shrink + wrap + ellipsis). Image elements draw a stored asset or a bound
 * image (e.g. the product photo); watermarks are just {@code type:image} with {@code opacity<1}.</p>
 *
 * <p>⚠️ Immutable (Builder only, no setters). Jackson (de)serializes through the Lombok builder so the
 * same rule holds for request bodies and the DB converter. Null soft fields are normalized at render
 * time by {@link com.pms.service.ThumbnailRenderer} (align→left/top, padding→0, maxLines→1, opacity→1,
 * color→#000000, lineSpacing→1.0, gradient/outline/border absent when their color is null or width &lt;= 0); {@code region},
 * {@code maxFontSize}, {@code minFontSize} and (for text) {@code fontId} are required — a null there is
 * rejected as {@link IllegalArgumentException} (→400).</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonDeserialize(builder = TemplateElement.TemplateElementBuilder.class)
public class TemplateElement {

    /** {@code text} | {@code image}. */
    private String type;

    /** Text: input argument name (e.g. {@code brandName}). Image: name of a bound image, or null. */
    private String bind;

    /** Image: storage key of a fixed source asset. Text: null. */
    private String src;

    /** Placement box in canvas pixels. Required. */
    private Region region;

    /** Horizontal/vertical alignment. Null → left/top. */
    private Align align;

    /** Inner padding in pixels. Null → 0 on every side. */
    private Padding padding;

    /** Text: {@link FontAsset} id used to render. Required for text. */
    private Long fontId;

    /** Text: hex color (e.g. {@code #000000}). Null → black. For a gradient fill this is the top color. */
    private String color;

    /**
     * Text: end color of a fill gradient; the start color is {@link #color}. Hex, e.g. {@code #FFFFFF}.
     * Null → solid {@link #color}.
     */
    private String gradientColor;

    /**
     * Text: gradient direction in degrees, clockwise from top→bottom. 0 = top→bottom (default),
     * 90 = left→right, 180 = bottom→top, 270 = right→left. Null → 0. Only used when {@link #gradientColor}
     * is set.
     */
    private Integer gradientAngle;

    /** Text autofit upper bound. Required for text. */
    private Integer maxFontSize;

    /** Text autofit lower bound. Required for text. */
    private Integer minFontSize;

    /** Text: max lines after wrapping (then shrink, then ellipsize). Null → 1. */
    private Integer maxLines;

    /**
     * Text: line-height multiplier applied to the font's natural line height (&gt;= 1.0). Null → 1.0.
     * Only has a visual effect on multi-line text; feeds both the autofit height budget and drawing.
     */
    private Double lineSpacing;

    /** Image: 0..1 opacity (watermark). Null → 1.0. */
    private Double opacity;

    /** Text: glyph outline (stroke) color, e.g. {@code #FFFFFF}. Null → no outline. */
    private String outlineColor;

    /** Text: glyph outline stroke width in px. Null or &lt;= 0 → no outline. */
    private Integer outlineWidth;

    /** Any element: border color drawn around the region box, e.g. {@code #000000}. Null → no border. */
    private String borderColor;

    /** Any element: border width in px drawn around the region box. Null or &lt;= 0 → no border. */
    private Integer borderWidth;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonDeserialize(builder = Region.RegionBuilder.class)
    public static class Region {
        private Integer x;
        private Integer y;
        private Integer w;
        private Integer h;

        @JsonPOJOBuilder(withPrefix = "")
        public static class RegionBuilder {
        }
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonDeserialize(builder = Align.AlignBuilder.class)
    public static class Align {
        /** left | center | right */
        private String h;
        /** top | center | bottom */
        private String v;

        @JsonPOJOBuilder(withPrefix = "")
        public static class AlignBuilder {
        }
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonDeserialize(builder = Padding.PaddingBuilder.class)
    public static class Padding {
        private Integer top;
        private Integer bottom;
        private Integer left;
        private Integer right;

        @JsonPOJOBuilder(withPrefix = "")
        public static class PaddingBuilder {
        }
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class TemplateElementBuilder {
    }
}
