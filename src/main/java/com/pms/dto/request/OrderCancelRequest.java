package com.pms.dto.request;

import com.pms.domain.OrderCancelReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Seller cancel before shipping (FEATURE_2609_25 / PLAN D1 · D4 · FEATURE_2610_17 / D61).
 *
 * <p>The cancel unit is <b>line (option) × quantity</b> — unlike order confirmation (box unit, no quantity). The
 * screen fills one line from the order detail, but the contract stays an array (the server groups by account →
 * orderId → boxId and sends one POST per group).
 *
 * <p>Coupang lines use {@code reason} — enum deserialization rejects a value outside the list (400; the list's only
 * source is {@code GET /api/admin/orders/cancel-reasons}, the same enum). 11st lines (sale refusal 1638, D61) use
 * {@code reasonCode} (11st code, list from {@code GET /api/orders/elevenst-choices}) + {@code reasonText} (1~200
 * characters) and cancel the whole line — {@code quantity} is not sent to 11st. No reason field is required here:
 * the service checks each platform's reason before anything is sent (FEATURE_2610_17 / D71).
 *
 * <p>Record → accessors are generated (no Lombok).
 */
public record OrderCancelRequest(

        @NotEmpty(message = "취소할 주문 라인을 1건 이상 선택하세요")
        @Size(max = 50, message = "한 번에 50건까지 취소할 수 있습니다")
        @Valid
        List<Line> lines,

        /** Coupang reason; null for an 11st-only request. */
        OrderCancelReason reason,

        /** 11st sale-refusal reason code (D61); null for a Coupang-only request. */
        String reasonCode,

        /** 11st reason text, 1~200 characters (D61 · D39 ①); null for a Coupang-only request. */
        @Size(max = 200, message = "사유 글은 1~200자로 적어 주세요.") String reasonText) {

    /** Coupang-only request (the shape before FEATURE_2610_17). */
    public OrderCancelRequest(List<Line> lines, OrderCancelReason reason) {
        this(lines, reason, null, null);
    }

    /**
     * One line to cancel. For a Coupang line the server checks the {@code quantity} cap against
     * {@code purchasableQty()} again (D3) — an irreversible API, so we stop it with a 400 first instead of leaving it
     * to Coupang. An 11st line cancels the whole line, so its quantity is not sent (FEATURE_2610_17 / D61).
     */
    public record Line(
            @NotNull(message = "주문 라인 id가 필요합니다") Long orderItemId,
            @NotNull(message = "취소 수량이 필요합니다") @Min(value = 1, message = "취소 수량은 1개 이상이어야 합니다")
            Integer quantity) {
    }
}
