package com.pms.service;

import java.util.List;

/**
 * Result of a shipment confirm (invoice upload) run.
 *
 * external*Id values are stored as String, so the report ({@link FailedBox#shipmentBoxId}, {@link #unmatched}) keeps
 * String as well (only the Coupang request body converts them to Long).
 *
 * @param totalRows     parsed data rows (blank rows excluded)
 * @param matchedOrders orders confirmed as send targets — skipped orders excluded, fallback-confirmed orders included
 *                      (COUPANG and ELEVENST accounts, FEATURE_2610_17 / D2)
 * @param unmatched     orderIds of another platform, or with no order line that the Coupang fallback (shipping-label
 *                      PLAN D16) could not confirm either
 * @param succeeded     succeeded boxes (Coupang responseList succeed=true count · 11st delivery numbers sent)
 * @param failed        failure details
 * @param skipped       orders not sent, each with its reason (PLAN 2609_07 D1·D7 · FEATURE_2610_17 / D47)
 */
public record ShipmentConfirmResult(
        int totalRows,
        int matchedOrders,
        List<String> unmatched,
        int succeeded,
        List<FailedBox> failed,
        List<SkippedOrder> skipped) {

    /** D47: reason of every skipped row except the 11st paid ones (the wording the screen showed before). */
    public static final String ALREADY_SHIPPED_REASON = "이미 발송처리된 상태입니다.";
    /** D47: reason of an 11st order still waiting for [발주처리] (D2 · D20). */
    public static final String ELEVENST_ACKNOWLEDGE_FIRST_REASON = "11번가에서 발주처리를 먼저 해 주세요.";

    /** 실패 박스 상세. */
    public record FailedBox(String shipmentBoxId, String resultCode, String message) {
    }

    /**
     * An order left out of sending. {@code status} = the value actually judged = <b>neutral status</b>
     * ({@code OrderStatus} name, e.g. {@code SHIPPED}) — the Korean label is the client's job (PLAN 2609_07 D9).
     * Mixed boxes report <b>the first line's status</b>.
     *
     * <p>{@code reason} (FEATURE_2610_17 / D47) = the sentence the result-file screen shows per row; null on the
     * order-acknowledge result, whose screen has no reason column.
     */
    public record SkippedOrder(String orderId, String status, String reason) {
    }
}
