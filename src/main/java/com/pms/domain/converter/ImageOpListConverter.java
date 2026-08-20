package com.pms.domain.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.ImageOp;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * Maps {@code List<ImageOp>} to/from a JSON string column ({@code TEXT}). Mirror of
 * {@link TemplateElementListConverter} — applied explicitly via {@code @Convert} on
 * {@code ProcessingPreset.operations}. A JSON TEXT column (not a dialect JSON type) is used deliberately
 * for H2↔MySQL portability.
 *
 * <p>⚠️ An {@link AttributeConverter} is instantiated by Hibernate, NOT as a Spring bean — constructor
 * injection is impossible, so the {@link ObjectMapper} is a shared {@code static} constant. Any
 * (de)serialization failure surfaces as {@link IllegalArgumentException} (→400 via the global handler).</p>
 */
@Converter
public class ImageOpListConverter implements AttributeConverter<List<ImageOp>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<ImageOp>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<ImageOp> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize image ops: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ImageOp> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize image ops: " + e.getMessage(), e);
        }
    }
}
