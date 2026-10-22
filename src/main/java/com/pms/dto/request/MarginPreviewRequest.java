package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A price typed in the [가격 설정] window, to be judged before saving (FEATURE_2610_27 / D6 · D20; FEATURE_2610_28 /
 * D20 · D21).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Price to preview the margin of (nothing is stored or sent)")
public class MarginPreviewRequest {

    @NotNull(message = "가격을 입력하세요")
    @Schema(description = "Price typed in", example = "12900")
    private BigDecimal price;

    @NotBlank(message = "쇼핑몰을 지정하세요")
    @Schema(description = "Site of the price: the listing's platform (COUPANG · ELEVENST) or GMARKET · AUCTION on ESM",
            example = "COUPANG")
    private String site;
}
