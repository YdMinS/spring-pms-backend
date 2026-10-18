package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One recent purchase row (PLAN 2609_29 D9). A lazy per-product read — every seller by default, one seller when
 * the history box filters it (FEATURE_2610_09 D7 · D25).
 *
 * <p>⚠️ Hence {@code sellerName}: without a filter the rows of every seller come mixed, and the list cannot be read
 * unless each row shows whose it is.
 *
 * <p>totalAmount/unitPrice are null when the amount was not entered (FEATURE_2610_18 D68 「미기입」) or the row predates
 * FEATURE_2609_28 — never 0.
 */
public record PurchaseRecordView(
        Long id,
        LocalDate purchasedOn,
        int quantity,
        BigDecimal totalAmount,        // null 가능 — 금액 미상
        BigDecimal unitPrice,          // null 가능
        boolean reflectToBasePrice,
        String sellerName,
        Long receiptId                 // null = per-product check-in; set = [영수증] (FEATURE_2610_18 D93 ①)
) {}
