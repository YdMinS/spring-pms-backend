package com.pms.domain.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.CollageLayout;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link CollageLayout} to/from a JSON string column ({@code TEXT}) — FEATURE_2610_21 D38 · D47. Mirror of
 * {@link ImageOpListConverter}: a JSON TEXT column (not a dialect JSON type) for H2↔MySQL portability.
 *
 * <p>⚠️ Instantiated by Hibernate, not as a Spring bean — the {@link ObjectMapper} is a shared {@code static}
 * constant. null ↔ null (a switched-off stage keeps no layout, D42). A (de)serialization failure surfaces as
 * {@link IllegalArgumentException} (→400).</p>
 */
@Converter
public class CollageLayoutConverter implements AttributeConverter<CollageLayout, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(CollageLayout attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize collage layout: " + e.getMessage(), e);
        }
    }

    @Override
    public CollageLayout convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, CollageLayout.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize collage layout: " + e.getMessage(), e);
        }
    }
}
