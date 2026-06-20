package com.pms.dto.response;

import java.time.LocalDate;

/** 라인 토글 안에서 보여줄 개별 구매 이력. */
public record PurchaseRecordView(
        Long id,
        LocalDate purchasedOn,
        int quantity
) {}
