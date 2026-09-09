package com.pms.dto.response;

/**
 * 출고 대상 주문 라인이 소진하는 물품 1건 (FEATURE_2609_28 / PLAN D11·D13).
 *
 * @param requiredQty  마스터 BOM 수량 × 주문 수량
 * @param confirmedQty 이미 기록된 {@code STOCK_OUT} 합 — <b>양수</b>
 */
public record OutboundProductLine(Long productId, String productName, int requiredQty, int confirmedQty) {

    /** 아직 내보내지 않은 수량. 화면이 다시 빼지 않도록 서버가 계산해 준다. */
    public int remainingQty() {
        return requiredQty - confirmedQty;
    }
}
