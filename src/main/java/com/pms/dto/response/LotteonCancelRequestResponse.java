package com.pms.dto.response;

import java.time.LocalDateTime;

/**
 * One open LotteON cancel request of an order line (FEATURE_2611_23 / D24 · D71) — the order detail's 「취소 요청」
 * row with its [취소 승인] · [취소 거부] buttons.
 *
 * @param id          {@code lotteon_cancel_request.id} — the path key of approve / reject
 * @param quantity    requested quantity ({@code cnclQty}) — the {n} of the approve confirmation
 * @param reason      D71 label 「사유 이름 — 사유 글」
 * @param requestedAt request time ({@code clmReqDttm}), KST wall clock; may be null
 */
public record LotteonCancelRequestResponse(Long id, int quantity, String reason, LocalDateTime requestedAt) {
}
