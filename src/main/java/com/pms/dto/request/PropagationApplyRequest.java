package com.pms.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 원가 파급 확정 요청 (FEATURE_2609_28 / PLAN D4 ②).
 *
 * <p>미리보기가 준 마스터 id 중 사람이 고른 것만 담는다. 🔴 <b>미리보기 없이 이 요청만 부르는 화면 경로를
 * 만들지 않는다</b> — 숫자를 보고 멈출 기회가 사라진다.
 */
public record PropagationApplyRequest(@NotEmpty List<Long> masterIds) {
}
