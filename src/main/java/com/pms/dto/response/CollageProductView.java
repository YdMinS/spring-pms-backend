package com.pms.dto.response;

import java.util.List;

/**
 * One component of a collage master (FEATURE_2610_21 D18 · FEATURE_2610_32 D20): {@code imageUrl} = its cover photo,
 * null when it has none (the editor shows 「사진 없음 — {productName}」); {@code images} = its gallery in gallery
 * order, the photos a cell may choose instead of the cover.
 */
public record CollageProductView(Long productId, String productName, String imageUrl,
                                 List<CollageProductImageView> images) {
}
