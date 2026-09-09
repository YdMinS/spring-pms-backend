package com.pms.dto.request;

import com.pms.domain.StockMovementType;
import com.pms.domain.StockReason;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One physical stock movement a person is confirming (FEATURE_2609_28 / PLAN D5~D10).
 *
 * <p>⚠️ {@code quantity} is sent <b>unsigned for DISPOSAL</b> ("3 items discarded") — the server
 * flips the sign. Letting the client choose the sign makes web and mobile drift apart.
 * {@code ADJUST} is the one exception: a count difference is genuinely signed.
 *
 * <p>⚠️ Not accepted here on purpose: {@code createdBy} (taken from the security context, D9),
 * {@code location} (resolved server-side, D16/D17) and {@code orderLineId} (STOCK_OUT only,
 * prompt 05). {@code unitPrice} is only honoured for {@code OPENING}.
 *
 * <p>Per-type rules (required reason, required/forbidden references) live in
 * {@code StockLedgerServiceImpl} — one place, so extra screens cannot invent their own.
 */
public record StockMovementRequest(
        @NotNull Long productId,
        @NotNull StockMovementType movementType,
        @NotNull Integer quantity,
        StockReason reason,
        String reasonNote,
        BigDecimal unitPrice,
        Long orderClaimId,
        Long purchaseRecordId,
        @NotNull LocalDate movedOn
) {
}
