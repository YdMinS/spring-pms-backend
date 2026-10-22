package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** A price typed in the [가격 설정] window, to be judged before saving (FEATURE_2610_27 / D6 · D20). */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Price to preview the margin of (nothing is stored or sent)")
public class MarginPreviewRequest {

    @NotNull(message = "가격을 입력하세요")
    @Schema(description = "Price typed in", example = "12900")
    private BigDecimal price;

    @Schema(description = "true = the Auction price of an ESM option", example = "false")
    private boolean auction;
}
