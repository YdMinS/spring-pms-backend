package com.pms.domain;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A single image-processing operation (JSON value object, NOT a JPA entity) applied in order by
 * {@link com.pms.service.ImageProcessor} (FEATURE_2608_08). Mirror of {@link DetailBlock} — an immutable
 * builder POJO persisted as part of {@code ProcessingPreset.operations} via
 * {@link com.pms.domain.converter.ImageOpListConverter} (JSON TEXT column, H2/MySQL portable).
 *
 * <p>The operation list + the {@code type} discriminator are the only extension seam of the image engine:
 * later ops (color adjust, resize, …) are new {@code type}s, never schema changes. An unknown {@code type}
 * is skipped by the engine.</p>
 *
 * <p><b>v1 type</b> = {@code overlay} — burn a fixed library asset ({@code assetStorageKey}) onto the base
 * image at an {@code anchor} corner/center, scaled to {@code scalePercent} of the base's short side
 * (contain-fit), inset by {@code marginPercent}, at {@code opacity}.</p>
 *
 * <p>⚠️ Immutable (Builder only, no setters). Jackson (de)serializes through the Lombok builder so the
 * same rule holds for request bodies and the DB converter. Null geometry fields are normalized by the
 * engine (anchor → BOTTOM_RIGHT, opacity → 1.0, scalePercent → 20, marginPercent → 0).</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonDeserialize(builder = ImageOp.ImageOpBuilder.class)
public class ImageOp {

    /** v1 = {@code overlay}. An unknown type is skipped by the engine. */
    private String type;

    /** overlay = {@link TemplateAsset#getStorageKey()} of the overlay image (loaded via {@code getBytes}). */
    private String assetStorageKey;

    /** {@code TOP_LEFT}/{@code TOP_RIGHT}/{@code BOTTOM_LEFT}/{@code BOTTOM_RIGHT}/{@code CENTER}. Null → BOTTOM_RIGHT. */
    private String anchor;

    /** Overlay opacity 0..1 (SRC_OVER). Null → 1.0. */
    private Double opacity;

    /** Overlay long side as a percent of the base's short side (contain-fit). Null → 20. */
    private Integer scalePercent;

    /** Edge margin as a percent of the base's short side. Null → 0. */
    private Integer marginPercent;

    @JsonPOJOBuilder(withPrefix = "")
    public static class ImageOpBuilder {
    }
}
