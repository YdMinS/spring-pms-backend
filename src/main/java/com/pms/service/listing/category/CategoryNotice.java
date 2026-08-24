package com.pms.service.listing.category;

/**
 * One normalized "상품정보제공고시" (product-info disclosure) item (FEATURE_2608_06 / 47).
 *
 * <p>⚠️ {@code key} and {@code label} are currently the same value (Coupang {@code noticeCategoryDetailName});
 * the separate {@code label} field exists for the per-platform display name to diverge later (NAVER).</p>
 *
 * @param key      stable key (Coupang {@code noticeCategoryDetailName})
 * @param label    display label (currently == key)
 * @param required true when the platform marks it MANDATORY
 */
public record CategoryNotice(String key, String label, boolean required) {
}
