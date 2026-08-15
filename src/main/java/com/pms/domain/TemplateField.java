package com.pms.domain;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A user-defined input field declared by a {@link ThumbnailTemplate} (JSON value object, NOT a JPA
 * entity). Text elements bind to a field via {@code TemplateElement.bind == key}; at generation time the
 * bound value is {@code fieldValues override (non-blank) ?? defaultValue}.
 *
 * <p>{@code key} = bind target · {@code label} = UI display · {@code defaultValue} = required for custom
 * fields, may be blank for the reserved keys {@code brandName}/{@code productName}. Persisted as part of
 * {@code ThumbnailTemplate.fields} via {@link com.pms.domain.converter.TemplateFieldListConverter}.</p>
 *
 * <p>⚠️ Immutable (Builder only, no setters). Jackson (de)serializes through the Lombok builder so the
 * same rule holds for request bodies and the DB converter.</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonDeserialize(builder = TemplateField.TemplateFieldBuilder.class)
public class TemplateField {

    /** Bind target — a text element with {@code bind == key} renders this field. */
    private String key;

    /** UI display label (editor / generation panel). */
    private String label;

    /** Fallback value when no override is supplied. Required (non-blank) for custom keys. */
    private String defaultValue;

    @JsonPOJOBuilder(withPrefix = "")
    public static class TemplateFieldBuilder {
    }
}
