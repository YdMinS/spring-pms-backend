package com.pms.dto.response;

/**
 * 전개하지 못한 주문 라인 (FEATURE_2609_28 / PLAN D13).
 *
 * <p>🔴 전개 실패를 조용히 넘기지 않기 위해 존재하는 타입이다. 목록에서 빼 버리면 재고가 조용히 틀리지만,
 * 이렇게 따로 실어 보내면 <b>목록 누락</b>으로 사람 눈에 띄어 고칠 수 있다.
 *
 * @param reason {@code UNMAPPED_OPTION} | {@code NO_MASTER_OPTION} | {@code EMPTY_BOM}
 */
public record OutboundUnexpandedView(Long orderLineId, String externalOrderId, String itemName, String reason) {
}
