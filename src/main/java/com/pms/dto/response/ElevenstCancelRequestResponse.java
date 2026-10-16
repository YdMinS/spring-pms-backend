package com.pms.dto.response;

import java.time.LocalDateTime;

/**
 * One open 11st cancel request of an order line (FEATURE_2610_17 / D19 · D40 · D41) — the order detail's
 * 「취소 요청」 row with its [취소 승인] · [취소 거부] buttons.
 *
 * @param id              {@code elevenst_cancel_request.id} — the path key of approve / reject
 * @param quantity        requested quantity ({@code ordCnQty})
 * @param reason          D41 label 「사유 이름 — 사유 글」
 * @param requestedAt     request time, KST wall clock (D32)
 * @param faultRejectable [책임사유 오류로 거부] is shown — the buyer filed it ({@code requested_by} 01) and the line is
 *                        paid (D62). Decided here so the screens do not branch on raw codes
 */
public record ElevenstCancelRequestResponse(Long id, int quantity, String reason, LocalDateTime requestedAt,
                                            boolean faultRejectable) {
}
