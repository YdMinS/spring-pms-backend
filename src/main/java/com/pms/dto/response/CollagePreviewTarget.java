package com.pms.dto.response;

/**
 * A template the editor previews with (FEATURE_2610_21 D39 · D46). {@code listingId} null = the tenant default
 * template (no listing uses this stage). {@code slotWidth}/{@code slotHeight} = the template's first product-photo
 * slot, null when it has none.
 */
public record CollagePreviewTarget(
        Long listingId,
        String sellerName,
        String platform,
        int canvasWidth,
        int canvasHeight,
        Integer slotWidth,
        Integer slotHeight) {
}
