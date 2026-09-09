package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 라인 토글 안에서 보여줄 개별 구매 이력.
 *
 * totalAmount/unitPrice 는 FEATURE_2609_28 이전에 기록된 행에서 null 이다 — "금액 미상"이며 0 이 아니다.
 */
public record PurchaseRecordView(
        Long id,
        LocalDate purchasedOn,
        int quantity,
        BigDecimal totalAmount,        // null 가능 — 이 기능 이전 행
        BigDecimal unitPrice,          // null 가능
        boolean reflectToBasePrice
) {}
