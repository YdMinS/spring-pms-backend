package com.pms.domain;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A single block of a {@link DetailTemplate} (JSON value object, NOT a JPA entity).
 *
 * <p>Mirror of {@link TemplateElement} but for the flow-layout detail page: no absolute coordinates —
 * blocks are rendered top-to-bottom in array order by {@link com.pms.service.DetailHtmlRenderer}
 * (FEATURE_2608_06 / Step 2-1). Persisted as part of {@code DetailTemplate.blocks} via
 * {@link com.pms.domain.converter.DetailBlockListConverter} (JSON TEXT column). The block array is the
 * only extension seam of the detail model — new block kinds and per-block options are added as data,
 * never as schema changes.</p>
 *
 * <p><b>type</b> = {@code text} (bind a master field / free text) | {@code imageZone} (an ordered array
 * of input images referenced by {@code bind}=zoneId) | {@code asset} (a fixed library image reused via
 * {@code src}={@link TemplateAsset#getStorageKey()}, e.g. shipping/refund notices).</p>
 *
 * <p>⚠️ Immutable (Builder only, no setters). Jackson (de)serializes through the Lombok builder so the
 * same rule holds for request bodies and the DB converter.</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonDeserialize(builder = DetailBlock.DetailBlockBuilder.class)
public class DetailBlock {

    /** {@code text} | {@code imageZone} | {@code asset}. */
    private String type;

    /** text = field key (brandName/productName/custom). imageZone = zoneId. asset = null. */
    private String bind;

    /** asset = {@link TemplateAsset#getStorageKey()} (goes verbatim into {@code <img src>}). Otherwise null. */
    private String src;

    /** text = fallback copy (e.g. "무료배송") when the bound value is blank. Otherwise null. */
    private String defaultValue;

    /** Content column width as a percent (1..100). Null → 100. */
    private Integer widthPercent;

    /** Horizontal alignment: {@code left} | {@code center} | {@code right}. Null → left. */
    private String align;

    @JsonPOJOBuilder(withPrefix = "")
    public static class DetailBlockBuilder {
    }
}
