package com.pms.dto.response;

import com.pms.service.listing.category.CategoryAttribute;
import com.pms.service.listing.category.CategoryNotice;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Response for {@code GET /api/admin/master-products/{id}/category-meta} (FEATURE_2608_06 / 47): the
 * (platform × category) schema plus the master's current values. An <b>empty</b> schema is a normal 200.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Category meta schema + current master values")
public class CategoryMetaResponse {

    @Schema(description = "Required/optional attributes for this category (possibly empty)")
    private List<CategoryAttribute> attributes;

    @Schema(description = "Product-info disclosure items for this category (possibly empty)")
    private List<CategoryNotice> notices;

    @Schema(description = "The master's current stored values")
    private Values values;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Master-level current values (empty maps when unset)")
    public static class Values {

        @Schema(description = "Attribute values (attribute name -> value)")
        private Map<String, String> attributes;

        @Schema(description = "Notice values (notice key -> value)")
        private Map<String, String> notices;
    }
}
