package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Attach a market option id to a channel option that has none (2609_74/D13).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Market option id to attach to a channel option without one")
public class MarketLinkRequest {

    @NotBlank(message = "쿠팡 옵션 ID 를 선택하세요")
    @Schema(description = "Market option id (Coupang vendorItemId)", example = "987654321")
    private String vendorItemId;
}
