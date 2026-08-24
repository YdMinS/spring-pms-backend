package com.pms.service.listing.category;

import java.util.List;

/**
 * One normalized required/optional category attribute (FEATURE_2608_06 / 47). Platform-agnostic shape so the
 * NAVER adapter can reuse it later.
 *
 * @param name      attribute display name (Coupang {@code attributeTypeName})
 * @param required  true when the platform marks it MANDATORY
 * @param inputType {@code TEXT} / {@code SELECT} / {@code NUMBER} (adapter-normalized)
 * @param options   SELECT candidates (empty when not a SELECT input)
 */
public record CategoryAttribute(String name, boolean required, String inputType, List<String> options) {
}
