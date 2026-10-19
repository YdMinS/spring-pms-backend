package com.pms.dto.response;

/**
 * One component of a collage master (FEATURE_2610_21 D1 · D18): {@code imageUrl} = its cover photo, null when it
 * has none (the editor shows 「사진 없음 — {productName}」).
 */
public record CollageProductView(Long productId, String productName, String imageUrl) {
}
