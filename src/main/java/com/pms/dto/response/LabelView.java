package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * 차이 리포트 ①의 원인 라벨 1개 (FEATURE_2609_30 / 02 · PLAN D12 · D13).
 *
 * <p>🔴 라벨 금액의 합은 <b>총차액과 정확히 같다</b>({@code ROUNDING} 이 나머지를 흡수한다). 이 항등식이
 * 리포트가 거짓말하지 않는다는 유일한 보증이다.
 *
 * @param count  이 라벨에 기여한 라인 수
 * @param detail 경고·설명 문구(없으면 null). 잔차가 총차액의 20% 를 넘으면 그렇다고 적힌다
 */
public record LabelView(String label, BigDecimal amount, int count, String detail) {
}
