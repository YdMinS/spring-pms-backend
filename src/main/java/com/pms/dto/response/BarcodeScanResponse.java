package com.pms.dto.response;

/**
 * Result of reading a barcode from one uploaded image ({@code POST /api/admin/products/barcode-scan}).
 *
 * <p>Both fields are {@code null} when the image holds no readable barcode — that is a normal result
 * (200), not an error.</p>
 *
 * @param barcode the decoder's raw output, untouched (same rule as barcode extraction: no padding/hyphens)
 * @param format  ZXing format name ("EAN_13" / "EAN_8" / "UPC_A" / "CODE_128")
 */
public record BarcodeScanResponse(String barcode, String format) {

    /** No readable barcode in the image. */
    public static BarcodeScanResponse notFound() {
        return new BarcodeScanResponse(null, null);
    }
}
