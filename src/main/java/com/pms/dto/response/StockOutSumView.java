package com.pms.dto.response;

/**
 * (주문 라인 × 물품) 별 이미 기록된 {@code STOCK_OUT} 합계 — 내부 집계용 (FEATURE_2609_28 / PLAN D12).
 *
 * <p>⚠️ {@code quantity} 는 원장에 저장된 그대로 <b>음수</b>다. 화면으로 나가는
 * {@link OutboundProductLine#confirmedQty()} 는 양수로 뒤집힌 값이며, 뒤집는 책임은 서비스에 있다 —
 * 화면이 부호를 다시 만지면 웹·모바일이 갈린다.
 */
public record StockOutSumView(Long orderLineId, Long productId, long quantity) {
}
