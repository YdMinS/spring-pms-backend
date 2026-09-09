package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * 지급 묶음 레벨 조정 1행 (FEATURE_2609_30 / 02 · PLAN D8 · D13).
 *
 * <p>🔴 {@code guidance} 는 <b>서버가 소유</b>한다. 쿠팡은 차감 사유를 주지 않으므로 "플랫폼 확인 필요" 문구를
 * 서버가 내려줘야 프론트가 사유를 지어내지 않는다 — 이 문구가 곧 사용자의 문의 트리거다.
 *
 * @param amount 플랫폼이 준 원문 값(양수). 부호는 {@code type} 이 결정한다
 */
public record AdjustmentView(String type, BigDecimal amount, String note, String guidance) {
}
