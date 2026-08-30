package com.pms.service.listing.category;

/**
 * One normalized "상품정보제공고시" (product-info disclosure) item (FEATURE_2608_06 / 47, 61).
 *
 * <p>⚠️ {@code key} and {@code label} are currently the same value (Coupang {@code noticeCategoryDetailName});
 * the separate {@code label} field exists for the per-platform display name to diverge later (NAVER).</p>
 *
 * <p>{@code groupName} is the parent notice-category name (Coupang {@code noticeCategoryName}, e.g. "의류") used
 * for grouping/labelling in the UI and for the required {@code noticeCategoryName} field when registering (61).
 * It is nullable — a parse failure or a legacy CategoryNotice leaves it {@code null} (front renders "기타").</p>
 *
 * @param key       stable key (Coupang {@code noticeCategoryDetailName})
 * @param label     display label (currently == key)
 * @param required  true when the platform marks it MANDATORY
 * @param groupName parent notice-category name (Coupang {@code noticeCategoryName}), nullable
 */
public record CategoryNotice(String key, String label, boolean required, String groupName) {
}
