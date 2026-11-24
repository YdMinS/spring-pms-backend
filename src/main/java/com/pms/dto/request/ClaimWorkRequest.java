package com.pms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 「반품 재입고」 · 「교환 발송」 save (FEATURE_2611_34 D35 ④ · ⑦): every (claim × product) line with a count above
 * zero, at once.
 *
 * @param movedOn the day of the tab (screen default = today)
 */
public record ClaimWorkRequest(
        @NotNull LocalDate movedOn,
        @NotEmpty(message = "담은 물건이 없습니다.") @Valid List<Line> lines
) {
    /**
     * One (claim × product) line.
     *
     * @param orderClaimId the return or exchange claim
     * @param productId    a product listed in that claim's {@code items}
     * @param quantity     this time's count, unsigned
     */
    public record Line(
            @NotNull Long orderClaimId,
            @NotNull Long productId,
            @NotNull @Min(value = 1, message = "수량은 1 이상이어야 합니다.") Integer quantity
    ) {}
}
