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
 *
 * <p>{@code localRecordOnly} = 쿠팡이 거절해 <b>우리 장부에만</b> 기록한 회차(FEATURE_2609_70 / D6).
 * 이때는 예외가 아니라 <b>HTTP 200</b> 으로 내려가고 화면이 「쿠팡에는 반영되지 않았습니다」를 그린다.
 * 🔴 회수 송장 2액션({@code RETURN_COLLECT_INVOICE}·{@code RETURN_COLLECT_INVOICE_RESEND})에서만
 * {@code true} 가 될 수 있다 — 승인·거부가 실패했는데 200 을 주면 "됐다"고 읽힌다.
 */
public record ClaimActionResponse(Long claimId,
                                  ClaimAction action,
                                  boolean succeeded,
                                  String resultCode,
                                  String resultMessage,
                                  boolean localRecordOnly) {
}
