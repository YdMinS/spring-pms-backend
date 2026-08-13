package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Body for POST .../thumbnails/generate: per-field value overrides. Any field not supplied (or blank)
 * renders with its template default.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Thumbnail generate request (field value overrides)")
public class GenerateThumbnailRequest {

    @Schema(description = "Field key → value override; unspecified fields use the template default")
    private Map<String, String> fieldValues;
}
