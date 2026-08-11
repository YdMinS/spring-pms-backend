package com.pms.domain.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.TemplateElement;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * Maps {@code List<TemplateElement>} to/from a JSON string column ({@code TEXT}).
 *
 * <p>Applied explicitly via {@code @Convert} on {@code ThumbnailTemplate.elements}. A JSON TEXT column
 * (not a dialect JSON type) is used deliberately for H2↔MySQL portability.</p>
 *
 * <p>⚠️ An {@link AttributeConverter} is instantiated by Hibernate, NOT as a Spring bean — constructor
 * injection is impossible, so the {@link ObjectMapper} is a shared {@code static} constant. Any
 * (de)serialization failure surfaces as {@link IllegalArgumentException} (→400 via the global handler).</p>
 */
@Converter
public class TemplateElementListConverter implements AttributeConverter<List<TemplateElement>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<TemplateElement>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<TemplateElement> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize template elements: " + e.getMessage(), e);
        }
    }

    @Override
    public List<TemplateElement> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize template elements: " + e.getMessage(), e);
        }
    }
}
