package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * 「입고 작업」 barcode lookup (FEATURE_2610_19 D117 ② ④): found = a product owns this barcode. {@code imageUrl} = that
 * product's representative photo as stored, null = none (FEATURE_2611_02 D8 · D15).
 */
public record IntakeBarcodeView(boolean found, Long productId, String productName, BigDecimal price, String imageUrl) {

    public static IntakeBarcodeView notFound() {
        return new IntakeBarcodeView(false, null, null, null, null);
    }
}
