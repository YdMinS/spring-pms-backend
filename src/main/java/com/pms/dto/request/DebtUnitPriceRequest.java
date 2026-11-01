package com.pms.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** [단가 입력] · [단가 수정] on a debt row (FEATURE_2610_19 D141 ③ · D143 ③): one number, 0 or more, never empty. */
public record DebtUnitPriceRequest(
        @NotNull(message = "단가를 입력하세요.") @DecimalMin("0.0000") BigDecimal unitPrice
) {}
