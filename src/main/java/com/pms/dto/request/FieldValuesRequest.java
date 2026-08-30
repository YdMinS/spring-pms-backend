package com.pms.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Channel-level field-value override body (FEATURE_2608_06 / 12). Only null is rejected — an empty map is
 * allowed and means "clear the override" (fall back to master / template defaults). Blank values are stored
 * but naturally skipped at render time (non-blank condition) so those keys fall back to defaults too.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FieldValuesRequest {

    @NotNull
    private Map<String, String> fieldValues;
}
