package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the receipt list (FEATURE_2610_18 D94).
 *
 * <p>{@code cardAlias} null = the card was unknown (the screen shows 「카드 없음」). {@code firstProductName} = the
 * first line's product, {@code productCount} = number of lines (the screen shows 「첫 상품 외 N개」).
 * {@code totalAmount} = sum of the lines with an amount; null when every line was left blank (the screen shows
 * 「미기입」).</p>
 */
public record ReceiptSummaryView(
        Long id,
        LocalDate purchasedOn,
        Long payerSellerId,
        String payerSellerName,
        String cardAlias,
        String firstProductName,
        int productCount,
        BigDecimal totalAmount
) {}
