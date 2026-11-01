package com.pms.dto.request;

import com.pms.domain.StockReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 「입고 작업」 [입고 저장] (FEATURE_2610_19 D117 ⑥ · FEATURE_2611_01 D1 · D16 · D19): every scanned line at once, each
 * with its own seller and reason; the amount stays empty (filled with the registered price, D135).
 *
 * @param movedOn the intake day (screen default = today) — the purchase day and the stock day of every line
 */
public record StockIntakeRequest(
        @NotNull LocalDate movedOn,
        @NotEmpty(message = "입고할 상품을 담으세요.") @Valid List<Line> lines
) {
    /**
     * One scanned line.
     *
     * @param sellerId null = 「미정」 (an undecided purchase, D105); ignored on a one-seller account (D20)
     * @param reason   {@code PURCHASE} (null = PURCHASE) or {@code OPENING}; {@code OPENING} needs a seller (D13 · D19)
     */
    public record Line(
            @NotNull Long productId,
            @NotNull @Min(value = 1, message = "수량은 1 이상이어야 합니다.") Integer quantity,
            Long sellerId,
            StockReason reason
    ) {}
}
