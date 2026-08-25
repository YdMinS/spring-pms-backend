package com.pms.dto.response;

import com.pms.service.listing.category.CategoryAttribute;
import com.pms.service.listing.category.CategoryMetaSchema;
import com.pms.service.listing.category.CategoryNotice;

import java.util.List;

/**
 * Schema-only response for {@code GET /api/admin/category-lookup/{platform}/meta?categoryId=} (FEATURE_2608_06
 * / 57): the (platform × category) required attributes + notices, <b>without</b> any master values (the master
 * add screen has no master yet). The master endpoint keeps {@link CategoryMetaResponse} (schema + values). An
 * empty schema is a normal 200.
 *
 * @param attributes required/optional attributes for this category (possibly empty)
 * @param notices    product-info disclosure items for this category (possibly empty)
 */
public record CategoryMetaSchemaResponse(List<CategoryAttribute> attributes, List<CategoryNotice> notices) {

    public static CategoryMetaSchemaResponse from(CategoryMetaSchema schema) {
        return new CategoryMetaSchemaResponse(schema.attributes(), schema.notices());
    }
}
