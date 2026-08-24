package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Body for {@code PATCH /api/admin/master-products/{id}/category-attributes} (FEATURE_2608_06 / 47). Both
 * maps are nullable ({@code null} = not entered / leave to fallback). No {@code @Setter} (immutable DTO).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Master-level category attribute + notice values")
public class CategoryAttributesRequest {

    @Schema(description = "Required-attribute values (attribute name -> value)", nullable = true)
    private Map<String, String> attributes;

    @Schema(description = "Product-info disclosure values (notice key -> value)", nullable = true)
    private Map<String, String> notices;
}
