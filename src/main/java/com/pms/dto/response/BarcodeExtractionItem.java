package com.pms.dto.response;

/**
 * 물품 하나의 추출 결과 한 줄 (FEATURE_2609_65 / PLAN D12).
 *
 * @param productId      대상 물품
 * @param productName    화면이 다시 조회하지 않도록 같이 싣는다
 * @param status         원인별 6종
 * @param barcode        읽어낸 값 — {@code EXTRACTED} · {@code DUPLICATE} 일 때만 채운다
 * @param format         "EAN_13" 등 ZXing 포맷명 — {@code EXTRACTED} 일 때만
 * @param productImageId 값을 읽어낸 사진. 레거시 대표 이미지({@code product.imageUrl})에서 읽었으면 null
 */
public record BarcodeExtractionItem(Long productId, String productName,
                                    BarcodeExtractionStatus status,
                                    String barcode,
                                    String format,
                                    Long productImageId) {
}
