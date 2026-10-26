package com.pms.service;

/**
 * Where a stage's single-photo thumbnail base comes from (FEATURE_2610_32 D14 · D31 · D32) — the six status texts:
 * OWN 「따로 고름」 · SELLER 「판매자 것을 따름」 · MASTER 「마스터 썸네일 사진을 따름」 · COVER 「대표사진을 따름」 ·
 * LEGACY_UPLOAD 「옛 바탕사진을 따름」 · FIRST_COMPONENT 「첫 구성품 사진을 따름」.
 */
public enum ThumbnailPhotoSource {
    OWN,
    SELLER,
    MASTER,
    COVER,
    LEGACY_UPLOAD,
    FIRST_COMPONENT
}
