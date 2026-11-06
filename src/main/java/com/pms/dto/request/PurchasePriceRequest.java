package com.pms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 「일별 입출고 내역」 [매입가 넣기] (FEATURE_2611_16 D2 · D3 · D7 · D12): the JSON part next to the optional receipt photo.
 *
 * @param cardId the card of a new receipt (D12) — read only when the photo makes a new receipt; null =
 *               「카드로 알 수 없음」
 * @param lines  one line per checked purchase (a decided incoming row's record or a share of an undecided row)
 */
public record PurchasePriceRequest(
        Long cardId,
        @NotEmpty(message = "매입가를 넣을 줄을 고르세요.") @Valid List<Line> lines
) {
    /**
     * One checked purchase. Both amounts blank = its amount and base price choice stay (D7); otherwise only one of
     * total and unit price.
     *
     * @param reflectToBasePrice 「상품 기준가에 반영」 (D3) — null → true
     */
    public record Line(
            @NotNull Long recordId,
            @DecimalMin("0.00") BigDecimal totalAmount,
            @DecimalMin("0.0000") BigDecimal unitPrice,
            Boolean reflectToBasePrice
    ) {}
}
