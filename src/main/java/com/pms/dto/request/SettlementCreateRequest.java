package com.pms.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 「돈으로 정리」 (FEATURE_2610_19 D90 · D99 ①): the seller who paid, the other seller of the pair, the day, the
 * amount (1 won or more, no upper limit) and a memo.
 */
public record SettlementCreateRequest(
        @NotNull Long payerSellerId,
        @NotNull Long receiverSellerId,
        @NotNull LocalDate settledOn,
        @NotNull @DecimalMin(value = "1", message = "금액은 1원 이상이어야 합니다.") BigDecimal amount,
        @Size(max = 500) String memo
) {}
