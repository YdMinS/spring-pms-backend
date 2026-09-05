package com.pms.service.claim;

import com.pms.domain.ClaimAction;

import java.util.List;

/**
 * "지금 이 claim 에서 누를 수 있는 액션" 1개 (FEATURE_2609_21 / PLAN D1).
 *
 * <p>서버가 내려주고 UI 는 렌더만 한다. 프론트가 {@code if (platform === 'COUPANG')} 로 분기하기
 * 시작하면 네이버를 붙일 때 화면 3벌을 다시 짠다.
 *
 * @param label        표시명 — <b>어댑터가 채운다</b>(D18)
 * @param requires     추가 입력 종류. 클라이언트는 모르는 값이면 버튼을 렌더하지 않는다
 * @param choices      {@code requires} 가 값 선택을 요구할 때만 채워진다(D19). 비면 UI 는 선택지를 안 그린다
 * @param irreversible 되돌릴 수 없는 액션인가 — UI 2단 확인의 근거(D10)
 */
public record ClaimActionOption(ClaimAction action,
                                String label,
                                ClaimAction.Requires requires,
                                List<ActionChoice> choices,
                                boolean irreversible) {
}
