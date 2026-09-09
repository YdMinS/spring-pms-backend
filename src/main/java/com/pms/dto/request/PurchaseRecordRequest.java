package com.pms.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 입고 1회 입력 — 물품 × 판매자 (PLAN 2609_29 D1·D3).
 *
 * <p>주문 라인이 아니라 물품에 붙는다: 입고는 "이 주문 몫"이 아니라 "이 판매자가 이만큼 들였다"이다.
 * quantity 는 정정(음수) 허용이라 @Min 이 없다.
 *
 * <p>금액은 총액 또는 단가 중 <b>하나만</b> 보낸다(FEATURE_2609_28 / PLAN D2) — 둘 다 오면 400.
 * 나머지 한쪽은 서버가 계산해 둘 다 저장한다({@link com.pms.domain.PurchaseRecord#of}).
 * 둘 다 null 이면 "금액 미상" 행으로 저장된다 — 0 으로 치환하지 않는다.
 *
 * <p>⚠️ {@code recordStock} 은 <b>전송 전용</b>이다(D19) — purchase_record 에 컬럼이 없고 엔티티도 읽지
 * 않는다. 저장되는 건 그 결과인 stock_movement 행의 유무뿐이다. null → true(즉시 반영).
 */
public record PurchaseRecordRequest(
        @NotNull Long productId,
        @NotNull Long sellerId,
        @NotNull LocalDate purchasedOn,
        @NotNull Integer quantity,                    // 음수 허용(정정)
        @DecimalMin("0.00") BigDecimal totalAmount,   // nullable — 단가 입력 모드
        @DecimalMin("0.0000") BigDecimal unitPrice,   // nullable — 총액 입력 모드
        Boolean reflectToBasePrice,                   // null → true (PLAN 2609_28 D3 기본값)
        Boolean recordStock                           // null → true (PLAN 2609_29 D19)
) {}
