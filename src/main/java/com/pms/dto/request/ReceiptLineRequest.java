package com.pms.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * One product line of a receipt (FEATURE_2610_18 D76 · D96 · D97). No seller field — every line belongs to the
 * receipt's payer seller (D89).
 *
 * <p>Amount rules are the check-in's ({@code PurchaseRecord.of}): only one of total and unit price, both null = amount
 * not entered (D41).</p>
 */
public record ReceiptLineRequest(
        @NotNull Long productId,
        @NotNull @Min(value = 1, message = "수량은 1 이상이어야 합니다.") Integer quantity,
        @DecimalMin("0.00") BigDecimal totalAmount,
        @DecimalMin("0.0000") BigDecimal unitPrice,
        Boolean reflectToBasePrice                   // null → true (PLAN 2609_28 D3 default)
) {}
