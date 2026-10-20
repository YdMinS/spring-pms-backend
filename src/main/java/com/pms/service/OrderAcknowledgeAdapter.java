package com.pms.service;

import com.pms.domain.OrderLine;
import com.pms.domain.Platform;
import com.pms.service.ShipmentConfirmResult.FailedBox;
import com.pms.service.ShipmentConfirmResult.SkippedOrder;

import java.util.List;

/**
 * Per-marketplace order confirmation ([발주처리]) — FEATURE_2610_23 / D21, the same shape as {@code ClaimSyncAdapter}.
 * Implementations: {@link CoupangOrderAcknowledgeAdapter} · {@link ElevenstOrderAcknowledgeAdapter}.
 *
 * <p>{@link OrderAcknowledgeServiceImpl} sorts the lines first (no adapter · no delivery bundle = unsupported, not
 * paid = skipped) and hands each adapter only its own paid lines. A marketplace without an adapter keeps the
 * "unsupported" result (D14).
 *
 * <p>{@link #internalStage()} is the one switch of the internal-stage bundle (FEATURE_2610_23 / D23 · D24) — the
 * 「내부 상품준비중」 mark · release, its sheet, the reservation and the internal [지금 발송] open together for a
 * marketplace whose adapter returns {@code true}. {@link InternalStageMarkets} reads it for the other services.
 */
public interface OrderAcknowledgeAdapter {

    /** The value compared with {@code marketplace_account.platform}. */
    Platform platform();

    /**
     * Whether this marketplace opens the internal-stage bundle (D23 · D24). Default: closed. Returning true also
     * requires overriding ShippingLabelAdapter#collectInternalRows and ShipmentInvoiceAdapter#sendReservedInvoices —
     * both throw by default.
     */
    default boolean internalStage() {
        return false;
    }

    /**
     * Sends the confirmation of this marketplace's paid lines (every line has a delivery bundle id) and writes
     * {@code PREPARING} back to the lines that succeeded.
     *
     * <p>⚠️ Never throws — a failed send is a {@link FailedBox} so the other marketplaces of the same request keep
     * their results.
     */
    AcknowledgeOutcome acknowledge(List<OrderLine> lines);

    /**
     * One marketplace's result.
     *
     * @param targets         the units sent (Coupang boxes · 11st (delivery number, product number) groups)
     * @param succeeded       units the marketplace accepted
     * @param failed          units the marketplace refused or that never got an answer
     * @param skipped         orders this marketplace left out (11st reserved sale, FEATURE_2610_17 / D67)
     * @param succeededBoxIds delivery bundle ids whose confirmation succeeded — read only for an internal-stage
     *                        marketplace (the release after a manual confirmation · the reservation run)
     */
    record AcknowledgeOutcome(int targets, int succeeded, List<FailedBox> failed, List<SkippedOrder> skipped,
                              List<String> succeededBoxIds) {
    }
}
