package com.pms.dto.response;

import java.math.BigDecimal;

/** One line of a receipt detail. totalAmount/unitPrice null = amount not entered (FEATURE_2610_18 D41 · D68). */
public record ReceiptLineView(
        String productName,
        int quantity,
        BigDecimal totalAmount,
        BigDecimal unitPrice,
        boolean reflectToBasePrice
) {}
