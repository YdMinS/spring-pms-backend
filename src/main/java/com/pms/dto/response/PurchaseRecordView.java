package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 최근 구매이력 한 줄 (PLAN 2609_29 D9). 물품 기준 지연 조회 — <b>판매자 조건이 없다</b>.
 *
 * <p>⚠️ 그래서 {@code sellerName} 을 포함한다: 판매자 무관으로 섞여 나오므로 각 줄이 누구 것인지
 * 보이지 않으면 목록이 읽히지 않는다.
 *
 * <p>totalAmount/unitPrice 는 FEATURE_2609_28 이전에 기록된 행에서 null 이다 — "금액 미상"이며 0 이 아니다.
 */
public record PurchaseRecordView(
        Long id,
        LocalDate purchasedOn,
        int quantity,
        BigDecimal totalAmount,        // null 가능 — 금액 미상
        BigDecimal unitPrice,          // null 가능
        boolean reflectToBasePrice,
        String sellerName
) {}
