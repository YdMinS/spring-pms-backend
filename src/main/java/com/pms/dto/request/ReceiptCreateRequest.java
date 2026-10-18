package com.pms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * The JSON part of a receipt check-in (FEATURE_2610_18 D76 · D87). The photo travels as the {@code photo} part.
 *
 * <p>{@code cardId} set → the payer seller is the card owner and {@code payerSellerId} is ignored. {@code cardId}
 * null (「카드로 알 수 없음」) → {@code payerSellerId} is required (D87).</p>
 */
public record ReceiptCreateRequest(
        @NotNull LocalDate purchasedOn,
        Long cardId,
        Long payerSellerId,
        @NotEmpty(message = "상품 줄을 하나 이상 넣으세요.") @Valid List<ReceiptLineRequest> lines
) {}
