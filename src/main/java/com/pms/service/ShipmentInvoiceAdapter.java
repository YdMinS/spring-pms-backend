package com.pms.service;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.Platform;
import com.pms.dto.request.ManualShipmentRequest;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.ShipmentConfirmResult.SkippedOrder;

import java.util.List;
import java.util.Map;

/**
 * Per-marketplace invoice registration (the result file · the single manual invoice · the stored invoices of the
 * internal stage) — FEATURE_2610_23 / D21, the same shape as {@code ClaimSyncAdapter}. Implementations:
 * {@link CoupangShipmentInvoiceAdapter} · {@link ElevenstShipmentInvoiceAdapter}.
 *
 * <p>{@link ShipmentConfirmServiceImpl} parses the file, finds each order in the DB and hands every adapter its own
 * orders, in the platform order (Coupang → 11st). A marketplace without an adapter keeps today's result (unmatched in
 * the file, 400 for the single invoice — D14). The internal-stage orders of the file (D23 · D24) are not handed to
 * {@link #shipFromFile} — the service sends them through {@link #sendReservedInvoices}.
 *
 * <p>⚠️ Never throw from {@link #shipFromFile} — every failure is a {@link FailedBox} so the other orders of the same
 * file keep their results.
 */
public interface ShipmentInvoiceAdapter {

    /** The value compared with {@code marketplace_account.platform}. */
    Platform platform();

    /**
     * The result file's orders of this marketplace (found in the DB, internal-stage orders left out).
     *
     * @param file   the parsed file — every adapter gets the same one
     * @param orders this marketplace's orders, order id → its lines, in file order (may be empty)
     */
    FileOutcome shipFromFile(ResultFile file, Map<String, List<OrderLine>> orders);

    /**
     * One invoice typed on the order detail for the anchor line's bundle.
     *
     * @param invoiceNumber already normalized (hyphens and spaces removed)
     */
    ManualShipmentResult shipManual(ManualShipmentRequest request, OrderLine anchor, MarketplaceAccount account,
                                    String invoiceNumber);

    /**
     * Internal-stage slot (FEATURE_2610_23 / D23 · D24): sends the stored invoices of internal-stage bundles (the
     * reservation run · [지금 발송]) and writes {@code SHIPPED} back to what succeeded. The default throws — callers
     * ask {@link InternalStageMarkets} first.
     *
     * @param invoices        this marketplace's invoices, one per bundle, in request order
     * @param linesByShipment bundle id → its lines (every bundle of {@code invoices} has lines)
     */
    default ReservedInvoiceResult sendReservedInvoices(List<ReservedInvoice> invoices,
                                                       Map<Long, List<OrderLine>> linesByShipment) {
        throw new UnsupportedOperationException(platform() + " 내부 단계 미지원");
    }

    /**
     * The parsed result file.
     *
     * @param invoicesByOrderId order id → invoices (file order kept, deduped — PLAN 2609_40 D8)
     * @param missingOrderIds   the file's order ids with no line in the DB, in file order — only the Coupang adapter
     *                          looks them up (FEATURE_2610_23 / D25); every other adapter leaves them
     * @param carrierCodeCache  platform → carrier code for the physical boxes, read once per platform (PLAN 2609_40 D6)
     */
    record ResultFile(Map<String, List<String>> invoicesByOrderId, List<String> missingOrderIds,
                      Map<Platform, String> carrierCodeCache) {
    }

    /**
     * One marketplace's result-file outcome.
     *
     * @param matchedOrders orders confirmed as send targets (skipped orders excluded)
     * @param succeeded     succeeded units (Coupang responseList succeed=true count · 11st delivery numbers sent)
     * @param failed        failure details
     * @param skipped       orders not sent, each with its reason
     * @param unmatched     order ids this adapter could not confirm (the Coupang lookup of missing orders)
     */
    record FileOutcome(int matchedOrders, int succeeded, List<FailedBox> failed, List<SkippedOrder> skipped,
                       List<String> unmatched) {
    }
}
