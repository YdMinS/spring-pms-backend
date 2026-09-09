package com.pms.service.settlement;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.SaleType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 플랫폼 어댑터 → 중립 적재 사이의 전달 객체 (FEATURE_2609_30 / PLAN D5·D6).
 *
 * <p>플랫폼 필드명은 여기서 끝난다 — 저장은 {@link SettlementLineUpserter} 가 중립 컬럼으로만 한다.
 * {@code raw} 만 플랫폼 미러({@code coupang_settlement_line})로 흘러간다.
 *
 * <p>🔴 필수 4개({@code externalOrderId}·{@code platformOptionId}·{@code saleType}·
 * {@code recognitionDate})가 비면 <b>라인을 버리지 않고 예외를 던진다</b>. 이 넷이 라인의 유일키라
 * 하나라도 없으면 멱등 upsert 자체가 성립하지 않는다 — 조용히 버리면 재조회 때마다 중복이 쌓이거나
 * 금액이 사라진다.
 *
 * @param saleAmount         판매금액 (환불도 <b>양수</b>다. 방향은 {@code saleType} 이 정한다)
 * @param serviceFeeRatio    실측 수수료율(VAT 제외) — 06 이 읽는다
 * @param couponAmount       셀러 부담 쿠폰 합 (플랫폼 부담 쿠폰은 제외)
 * @param deliveryFeeAmount  배송비 분담 (원거리 배송비 포함)
 * @param raw                응답 원문 라인 1개 — 미러 저장용(필수)
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
        JsonNode raw) {

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
