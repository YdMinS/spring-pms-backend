package com.pms.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One check-in input — product × seller (PLAN 2609_29 D1 · D3).
 *
 * <p>It belongs to the product, not to an order line: a check-in means "this seller brought in this many", not
 * "this order's share". quantity allows corrections (negative), hence no @Min.
 *
 * <p>Send <b>only one</b> of total and unit price (FEATURE_2609_28 / PLAN D2) — both → 400. The server derives the
 * other side and stores both ({@link com.pms.domain.PurchaseRecord#of}). Both null → an "amount unknown" row — never
 * replaced with 0.
 *
 * <p>⚠️ {@code recordStock} is <b>request only</b> (D19) — purchase_record has no column for it and the entity
 * never reads it. null → true. It counts only while the stock link setting is on; with the setting off no stock is
 * written whatever it says (FEATURE_2610_09 D65).
 */
public record PurchaseRecordRequest(
        @NotNull Long productId,
        @NotNull Long sellerId,
        @NotNull LocalDate purchasedOn,
        @NotNull Integer quantity,                    // 음수 허용(정정)
        @DecimalMin("0.00") BigDecimal totalAmount,   // nullable — 단가 입력 모드
        @DecimalMin("0.0000") BigDecimal unitPrice,   // nullable — 총액 입력 모드
        Boolean reflectToBasePrice,                   // null → true (PLAN 2609_28 D3 기본값)
        Boolean recordStock                           // null → true; ignored while the stock link is off (D65)
) {}
