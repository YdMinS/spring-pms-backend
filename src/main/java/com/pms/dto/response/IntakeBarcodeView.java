package com.pms.dto.response;

import java.math.BigDecimal;

/** 「입고 작업」 barcode lookup (FEATURE_2610_19 D117 ② ④): found = a product owns this barcode. */
public record IntakeBarcodeView(boolean found, Long productId, String productName, BigDecimal price) {

    public static IntakeBarcodeView notFound() {
        return new IntakeBarcodeView(false, null, null, null);
    }
}
