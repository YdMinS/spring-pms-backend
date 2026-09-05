package com.pms.dto.response;

import com.pms.domain.ClaimAction;

/**
 * 클레임 처리 액션 결과 — POST /api/admin/claims/{id}/actions (FEATURE_2609_21 / PLAN §5).
 *
 * <p>실패도 같은 모양으로 내려간다(HTTP 502 + {@code data}) — 클라이언트가 성공/실패에서 서로 다른
 * 스키마를 파싱하지 않게.
 *
 * ⚠️ {@code resultMessage} 는 <b>쿠팡 원문 그대로</b>다(D15) — 번역·요약하면 실계정 디버깅에서
 * 검색이 안 된다.
 */
public record ClaimActionResponse(Long claimId,
                                  ClaimAction action,
                                  boolean succeeded,
                                  String resultCode,
                                  String resultMessage) {
}
