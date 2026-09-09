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
 * <p>⚠️ {@code sellerId} (PLAN 2609_29 D4·D22) is <b>required for every type except</b>
 * {@code RETURN_IN}, where the server derives it from the claim's order
 * (claim -> orderLine -> order -> marketplaceAccount -> seller) and the screen never asks.
 * It carries no {@code @NotNull} on purpose: the per-type rule is owned by the service, and an
 * annotation here would reject the derived case before the service ever sees it.
 * For {@code STOCK_IN + PURCHASE} it must equal the purchase record's seller — otherwise the money
 * ledger and the physical ledger disagree and nobody can tell later which one was right.
 *
 * <p>⚠️ Not accepted here on purpose: {@code createdBy} (taken from the security context, D9),
 * {@code location} (resolved server-side, D16/D17) and {@code orderLineId} (STOCK_OUT only,
 * prompt 05). {@code unitPrice} is only honoured for {@code OPENING}.
 *
 * <p>⚠️ This record has <b>no {@code @Builder}</b> — callers assemble it with positional arguments,
 * so the field ORDER below is part of the contract.
 *
 * <p>Per-type rules (required reason, required/forbidden references) live in
 * {@code StockLedgerServiceImpl} — one place, so extra screens cannot invent their own.
 */
public record StockMovementRequest(
        @NotNull Long productId,
        Long sellerId,
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
