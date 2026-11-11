package com.pms.dto.request;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The row popup of 「일별 입출고 내역」 (FEATURE_2611_17 D5): the JSON part next to the optional receipt photo — one row
 * or one share, saved at once.
 *
 * @param movementId         the ledger row (a decided incoming row) — exactly one of this and {@code shareRecordId}
 * @param shareRecordId      a share of an undecided intake row (its purchase record)
 * @param sellerId           the seller after the save; null or the current seller = no seller change (D10)
 * @param totalAmount        the price as a total — at most one of this and {@code unitPrice}; both null = the price
 *                           stays (D18)
 * @param unitPrice          the price per piece
 * @param reflectToBasePrice 「상품 기준가에 반영」 of a purchase (FEATURE_2611_16 D3) — null → true; not read for an
 *                           opening-stock or 「기타」 intake row (D17 · D18 · FEATURE_2611_22 D38)
 * @param receiptId          a receipt of that day with its photo to add the purchase to (D23 · D26); null = none
 * @param cardId             the card of a new receipt made from the photo (FEATURE_2611_16 D12); null =
 *                           「카드로 알 수 없음」
 * @param newMovedOn         the row's new day, saved last (FEATURE_2611_21 D9); null or the row's day = the day stays ·
 *                           a share takes none (D13)
 */
public record DailyRowEditRequest(
        Long movementId,
        Long shareRecordId,
        Long sellerId,
        @DecimalMin("0.00") BigDecimal totalAmount,
        @DecimalMin("0.0000") BigDecimal unitPrice,
        Boolean reflectToBasePrice,
        Long receiptId,
        Long cardId,
        LocalDate newMovedOn
) {}
