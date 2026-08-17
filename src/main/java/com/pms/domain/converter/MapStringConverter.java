package com.pms.domain.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Map;

/**
 * Maps {@code Map<String, String>} to/from a JSON string column ({@code TEXT}). Mirror of
 * {@link TemplateFieldListConverter} — applied explicitly via {@code @Convert} on
 * {@code MasterProduct.fieldValues}. A JSON TEXT column (not a dialect JSON type) is used deliberately
 * for H2↔MySQL portability.
 *
 * <p>⚠️ An {@link AttributeConverter} is instantiated by Hibernate, NOT as a Spring bean — constructor
 * injection is impossible, so the {@link ObjectMapper} is a shared {@code static} constant. Any
 * (de)serialization failure surfaces as {@link IllegalArgumentException} (→400 via the global handler).</p>
 */
@Converter
public class MapStringConverter implements AttributeConverter<Map<String, String>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Map<String, String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize field values: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize field values: " + e.getMessage(), e);
        }
    }
}
