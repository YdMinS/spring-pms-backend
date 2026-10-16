package com.pms.dto.response;

import com.pms.domain.ClaimAction;

/**
 * Claim action result — POST /api/admin/claims/{id}/actions (FEATURE_2609_21 / PLAN §5).
 *
 * <p>A failure comes in the same shape (HTTP 502 + {@code data}) — so clients do not parse different schemas for
 * success and failure.
 *
 * ⚠️ {@code resultMessage} is <b>the marketplace's original text</b> (Coupang · 11st, D15 · FEATURE_2610_17 / D15)
 * — translated or summarized, it cannot be searched when debugging a live account.
 *
 * <p>{@code localRecordOnly} = a round the marketplace rejected and only <b>our ledger</b> recorded
 * (FEATURE_2609_70 / D6 · FEATURE_2610_17 / D22). It comes as <b>HTTP 200</b>, not an exception, and the screen
 * shows 「마켓에는 반영되지 않았습니다」 (FEATURE_2610_17 / D18).
 * 🔴 Only the two pickup-invoice actions ({@code RETURN_COLLECT_INVOICE} · {@code RETURN_COLLECT_INVOICE_RESEND})
 * can be {@code true} — a 200 for a failed approve or reject reads as "done".
 */
public record ClaimActionResponse(Long claimId,
                                  ClaimAction action,
                                  boolean succeeded,
                                  String resultCode,
                                  String resultMessage,
                                  boolean localRecordOnly) {
}
