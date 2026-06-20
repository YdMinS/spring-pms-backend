package com.pms.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 라인 구매 기록 입력. quantity 는 정정(음수) 허용이라 @Min 없음. */
public record PurchaseRecordRequest(
        @NotNull LocalDate purchasedOn,
        @NotNull Integer quantity
) {}
