package com.pms.domain.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.TemplateField;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * Maps {@code List<TemplateField>} to/from a JSON string column ({@code TEXT}). Mirror of
 * {@link TemplateElementListConverter} — applied explicitly via {@code @Convert} on
 * {@code ThumbnailTemplate.fields}. A JSON TEXT column (not a dialect JSON type) is used deliberately
 * for H2↔MySQL portability.
 *
 * <p>⚠️ An {@link AttributeConverter} is instantiated by Hibernate, NOT as a Spring bean — constructor
 * injection is impossible, so the {@link ObjectMapper} is a shared {@code static} constant. Any
 * (de)serialization failure surfaces as {@link IllegalArgumentException} (→400 via the global handler).</p>
 */
@Converter
public class TemplateFieldListConverter implements AttributeConverter<List<TemplateField>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<TemplateField>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<TemplateField> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize template fields: " + e.getMessage(), e);
        }
    }

    @Override
    public List<TemplateField> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize template fields: " + e.getMessage(), e);
        }
    }
}
