package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One cause line of a seller pair (FEATURE_2610_19 D51 · D90 · D141 · D143).
 *
 * <p>kind {@code BORROW} = 「출고 차용」: from = the borrower (order seller), to = the lender; amount null = 「미기입」.
 * kind {@code SETTLEMENT} = 「돈 정리」: from = the payer, to = the receiver.
 *
 * @param canFillPrice [단가 입력] — the lot behind a 「미기입」 row has no unit price (D141)
 * @param canEditPrice [단가 수정] — that lot's unit price was typed on this screen (D143 ①)
 */
public record SellerDebtEntryView(
        String kind,
        Long id,
        LocalDate date,
        Long fromSellerId,
        String fromSellerName,
        Long toSellerId,
        String toSellerName,
        String orderNumber,
        String productName,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal amount,
        boolean canFillPrice,
        boolean canEditPrice,
        String memo,
        boolean cancelled
) {}
