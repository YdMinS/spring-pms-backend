package com.pms.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Receipt header edit (FEATURE_2610_18 D100): purchase day, card and payer seller only — the photo and the lines stay.
 *
 * <p>Same card rule as {@link ReceiptCreateRequest}: {@code cardId} set → the card owner pays and
 * {@code payerSellerId} is ignored; {@code cardId} null (「카드로 알 수 없음」) → {@code payerSellerId} is required.
 * The receipt's current card may be sent even when it is hidden (D100 ⑥).</p>
 */
public record ReceiptUpdateRequest(
        @NotNull LocalDate purchasedOn,
        Long cardId,
        Long payerSellerId
) {}
