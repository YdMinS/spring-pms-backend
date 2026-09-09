package com.pms.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 라인 구매 기록 입력. quantity 는 정정(음수) 허용이라 @Min 없음.
 *
 * 금액은 총액 또는 단가 중 <b>하나만</b> 보낸다(FEATURE_2609_28 / PLAN D2) — 둘 다 오면 400.
 * 나머지 한쪽은 서버가 계산해 둘 다 저장한다({@link com.pms.domain.PurchaseRecord#of}).
 * 둘 다 null 이면 "금액 미상" 행으로 저장된다 — 0 으로 치환하지 않는다.
 */
public record PurchaseRecordRequest(
        @NotNull LocalDate purchasedOn,
        @NotNull Integer quantity,
        @DecimalMin("0.00") BigDecimal totalAmount,   // nullable — 단가 입력 모드
        @DecimalMin("0.0000") BigDecimal unitPrice,   // nullable — 총액 입력 모드
        Boolean reflectToBasePrice                    // null → true (PLAN 2609_28 D3 기본값)
) {}
