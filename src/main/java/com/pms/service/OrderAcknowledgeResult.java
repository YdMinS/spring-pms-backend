package com.pms.service;

import com.pms.domain.Platform;

import java.util.List;

/**
 * Order-confirmation result.
 *
 * @param requestedLines lines found — the count the finder returned after id dedupe (missing ids are not counted)
 * @param targetBoxes    boxes actually sent (line → externalBoxId dedupe, PLAN 2609_17 D1); for 11st the
 *                       (delivery number, product number) groups (FEATURE_2610_17 / D48)
 * @param succeeded      succeeded boxes (Coupang responseList succeed=true · 11st groups accepted)
 * @param failed         failed box details — the marketplace's resultCode / message as given (D15)
 * @param skipped        <b>orders</b> not sent because not paid (D2, not lines). status = the neutral status judged;
 *                       reason = null (the acknowledge screen has no reason column, FEATURE_2610_17 / D47)
 * @param unsupported    order numbers that cannot be sent: neither COUPANG nor ELEVENST, or no externalBoxId (D10)
 * @param byPlatform     the same send split per marketplace — only marketplaces that had something to send, order
 *                       COUPANG → ELEVENST (FEATURE_2610_17 / D57). The fields above stay the sum of both
 */
public record OrderAcknowledgeResult(
        int requestedLines,
        int targetBoxes,
        int succeeded,
        List<ShipmentConfirmResult.FailedBox> failed,
        List<ShipmentConfirmResult.SkippedOrder> skipped,
        List<String> unsupported,
        List<PlatformResult> byPlatform) {

    /**
     * One marketplace's share of an order-confirmation send (FEATURE_2610_17 / D57).
     *
     * @param platform  COUPANG or ELEVENST
     * @param succeeded succeeded units — a Coupang box or an 11st (delivery number, product number) group (D54)
     * @param failed    that marketplace's failed units; {@code FailedBox} is the record shared with shipment, unchanged
     */
    public record PlatformResult(Platform platform, int succeeded, List<ShipmentConfirmResult.FailedBox> failed) {
    }
}
