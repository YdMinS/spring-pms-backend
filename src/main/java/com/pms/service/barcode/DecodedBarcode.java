package com.pms.service.barcode;

/**
 * One barcode read out of an image (FEATURE_2609_65).
 *
 * @param text   the decoder's raw output — stored into {@code products.barcode_id} untouched (PLAN/D3)
 * @param format ZXing format name ("EAN_13" / "EAN_8" / "UPC_A" / "CODE_128"), shown next to the value
 *               so the user can judge a 12-digit {@code UPC_A} against a scanner that sends 13 (PLAN/D3)
 */
public record DecodedBarcode(String text, String format) {
}
