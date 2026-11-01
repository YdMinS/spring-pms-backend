package com.pms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 「영수증 없이 판매자 정하기」 of the daily in/out view (FEATURE_2611_01 D2 · D4 · D5 · D8).
 *
 * <p>The lines are the receipt assignment's lines (product · quantity · total or unit · base price check). The purchase
 * day is the screen day — there is no day field (D8).</p>
 *
 * @param mode     {@code RECEIPT_LATER} = a receipt without a photo, its photo added later (D4) · {@code NO_RECEIPT} =
 *                 no receipt, the records are marked as found stock (D12)
 * @param sellerId the seller who owns the lines (required — never back to 「미정」)
 */
public record ReceiptlessAssignRequest(
        @NotNull Mode mode,
        Long sellerId,
        @NotEmpty(message = "상품 줄을 하나 이상 넣으세요.") @Valid List<ReceiptLineRequest> lines
) {
    /** 「영수증 나중에」 / 「영수증 없음(발견 재고 등)」 (D4). */
    public enum Mode { RECEIPT_LATER, NO_RECEIPT }
}
