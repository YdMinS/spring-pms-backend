package com.pms.dto.request;

import com.pms.domain.StockReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 「출고 작업」 [폐기 저장] · [매입처 반품 저장] (FEATURE_2611_34 D35 ⑤): every line of the tab at once, each with its
 * own seller and reason.
 *
 * @param movedOn the day of the tab (screen default = today)
 */
public record OutboundWorkRequest(
        @NotNull LocalDate movedOn,
        @NotEmpty(message = "담은 물건이 없습니다.") @Valid List<Line> lines
) {
    /**
     * One scanned line.
     *
     * @param reasonNote only for an {@code ETC} line
     * @param quantity   how many to take out, unsigned
     */
    public record Line(
            @NotNull Long productId,
            Long sellerId,
            @NotNull StockReason reason,
            String reasonNote,
            @NotNull @Min(value = 1, message = "수량은 1 이상이어야 합니다.") Integer quantity
    ) {}
}
