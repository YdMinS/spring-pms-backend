package com.pms.domain.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.DashboardCard;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * Maps the chosen dashboard cards to/from a JSON string column ({@code TEXT}) — FEATURE_2610_29 D6. Mirror of
 * {@link CollageLayoutConverter}: a JSON TEXT column (not a dialect JSON type) for H2↔MySQL portability.
 *
 * <p>⚠️ Instantiated by Hibernate, not as a Spring bean — the {@link ObjectMapper} is a shared {@code static}
 * constant. null ↔ empty list. A (de)serialization failure surfaces as {@link IllegalArgumentException} (→400).</p>
 */
@Converter
public class DashboardCardListConverter implements AttributeConverter<List<DashboardCard>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<DashboardCard>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<DashboardCard> attribute) {
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize dashboard cards: " + e.getMessage(), e);
        }
    }

    @Override
    public List<DashboardCard> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize dashboard cards: " + e.getMessage(), e);
        }
    }
}
