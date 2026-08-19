package com.pms.domain.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * Maps {@code List<String>} to/from a JSON string column ({@code TEXT}). Mirror of
 * {@link MapStringConverter} — applied explicitly via {@code @Convert} on the tag columns
 * ({@code MasterProduct.tags} / {@code ProductListing.tags} / {@code ProductListingTagRevision.tags}).
 * A JSON TEXT column (not a dialect JSON type) is used deliberately for H2↔MySQL portability.
 *
 * <p>⚠️ An {@link AttributeConverter} is instantiated by Hibernate, NOT as a Spring bean — constructor
 * injection is impossible, so the {@link ObjectMapper} is a shared {@code static} constant. Any
 * (de)serialization failure surfaces as {@link IllegalArgumentException} (→400 via the global handler).</p>
 *
 * <p>⚠️ {@code null} round-trips to {@code null} (an empty list serializes to {@code "[]"}) so a null column
 * stays distinct from an explicit empty list — {@code null} = "no tags" on {@code ProductListing}.</p>
 */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize tags: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize tags: " + e.getMessage(), e);
        }
    }
}
