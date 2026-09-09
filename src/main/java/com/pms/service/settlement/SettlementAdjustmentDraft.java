package com.pms.service.settlement;

import com.pms.domain.SettlementAdjustmentType;

import java.math.BigDecimal;

/**
 * 지급 묶음 레벨 조정 1건의 원문 값 (FEATURE_2609_30 / PLAN D8).
 *
 * <p>🔴 <b>금액은 플랫폼이 준 그대로(양수) 담는다.</b> 부호는 {@link SettlementAdjustmentType} 이 결정하며
 * 합산 시점에 {@link SettlementReconciler#signedAmount} 가 적용한다 — 여기서 −1 을 곱해 저장하면 원문과
 * 대조가 되지 않아 "왜 이 금액이 빠졌나"에 답할 수 없다.
 *
 * @param note 사유. 쿠팡은 차감 사유를 주지 않으므로 대개 null 이고, {@code OTHER} 일 때만 우리가 매핑하지
 *             못한 응답 필드명을 남긴다(D13 — 모르는 것을 아는 척하지 않는다).
 */
public record SettlementAdjustmentDraft(SettlementAdjustmentType type, BigDecimal amount, String note) {
}
