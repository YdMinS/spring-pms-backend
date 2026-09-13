package com.pms.dto.response;

/**
 * 이 박스에 담아야 할 것 1건 = (주문 라인 × 물품) (FEATURE_2609_40 / PLAN D10 · D11).
 *
 * <p>🔴 {@code barcodeId} 를 함께 내리는 이유가 전부다(D11): 화면이 스캔을 <b>로컬로</b> 맞춘다.
 * 이 필드가 빠지면 스캔마다 서버 왕복이 생겨 작업이 끊긴다 — 스캔은 초당 여러 번 들어온다.
 * 바코드가 비어 있는 물품은 화면에서 영원히 안 잡히므로, 등록되지 않은 바코드는 운영 문제로 드러난다.
 *
 * @param remainingQty 아직 안 나간 수량 = {@code 필요 − STOCK_OUT 합계}. 이미 완료된 다른 박스의 수량은
 *                     그 박스가 출고를 남겼으므로 <b>자동으로 빠져 있다</b>
 */
public record PackingRemainingItem(Long orderLineId, String itemName, Long productId,
                                   String productName, String barcodeId, int remainingQty) {
}
