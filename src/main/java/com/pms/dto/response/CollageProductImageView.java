package com.pms.dto.response;

/**
 * One gallery photo of a collage component (FEATURE_2610_32 D20) — {@code productImageId} goes into
 * {@code CollageCell.productImageId}; {@code imageUrl} is the stored value (the web resolves it like the cover).
 */
public record CollageProductImageView(Long productImageId, String imageUrl) {
}
