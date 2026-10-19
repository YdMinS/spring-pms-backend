package com.pms.service.settlement;

import com.pms.domain.SaleType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Transfer object between a platform adapter and the neutral load (FEATURE_2609_30 / PLAN D5 · D6).
 *
 * <p>Platform field names end here — {@link SettlementLineUpserter} stores neutral columns only. Only {@code raw}
 * flows on to the platform mirror ({@code coupang_settlement_line} · {@code elevenst_settlement_line},
 * FEATURE_2610_20).
 *
 * <p>🔴 When one of the four required values ({@code externalOrderId} · {@code platformOptionId} · {@code saleType} ·
 * {@code recognitionDate}) is missing, the line is <b>not dropped — an exception is thrown</b>. The four are the
 * line's unique key; without one of them an idempotent upsert is impossible, and dropping it silently either piles
 * up duplicates on every re-read or loses the amount.
 *
 * @param saleAmount         sale amount (a refund is <b>positive</b> too — {@code saleType} sets the direction)
 * @param serviceFeeRatio    measured commission rate (VAT excluded) — read by 06
 * @param couponAmount       sum of seller-funded coupons (platform-funded coupons excluded)
 * @param deliveryFeeAmount  delivery-fee share (remote-area surcharge included)
 * @param raw                one raw response line for the platform mirror (required) — Coupang = {@code JsonNode},
 *                           11st = the kept-field {@code Map<String, String>} (FEATURE_2610_20 / D16)
 */
public record SettlementLineDraft(
        String externalOrderId,
        String platformOptionId,
        SaleType saleType,
        LocalDate recognitionDate,
        LocalDate saleDate,
        LocalDate settlementDate,
        LocalDate finalSettlementDate,
        Integer quantity,
        BigDecimal saleAmount,
        BigDecimal serviceFee,
        BigDecimal serviceFeeVat,
        BigDecimal serviceFeeRatio,
        BigDecimal couponAmount,
        BigDecimal deliveryFeeAmount,
        BigDecimal settlementAmount,
        Object raw) {

    public SettlementLineDraft {
        require(externalOrderId != null && !externalOrderId.isBlank(), "주문번호");
        require(platformOptionId != null && !platformOptionId.isBlank(), "옵션 식별자");
        require(saleType != null, "매출/환불 구분");
        require(recognitionDate != null, "매출인식일");
        require(raw != null, "응답 원문");
    }

    private static void require(boolean condition, String field) {
        if (!condition) {
            throw new IllegalArgumentException("정산 라인에 " + field + "이(가) 없습니다 — 유일키를 만들 수 없습니다");
        }
    }

    /** 멱등 upsert 의 유일키 문자열. 같은 실행 안에서 이 키가 두 번 나오면 중복이다(D10). */
    public String uniqueKey(Long marketplaceAccountId) {
        return marketplaceAccountId + "|" + externalOrderId + "|" + platformOptionId
                + "|" + saleType + "|" + recognitionDate;
    }
}
