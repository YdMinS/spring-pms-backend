package com.pms.service.listing.category;

import java.util.List;

/**
 * The normalized required-attribute + notice schema for a (platform × category) (FEATURE_2608_06 / 47).
 * An <b>empty</b> schema (both lists empty) is a first-class case — the platform simply has no required
 * attributes/notices for that category (e.g. NAVER placeholder, or a parse/empty response).
 *
 * @param attributes required/optional attributes ({@link CategoryAttribute})
 * @param notices    product-info disclosure items ({@link CategoryNotice})
 */
public record CategoryMetaSchema(List<CategoryAttribute> attributes, List<CategoryNotice> notices) {
}
