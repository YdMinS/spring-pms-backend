package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Response of the register (push) endpoint (FEATURE_2608_06 / 3c). Returns immediately with SUBMITTED —
 * approval is NOT awaited (detected later by fetch-status / sync-approvals).
 */
@Getter
@Builder
@Schema(description = "Result of pushing a DRAFT cell to the market (SUBMITTED, no approval wait)")
public class ListingRegisterResponse {

    @Schema(description = "Product listing (cell) id", example = "1")
    private Long productListingId;

    @Schema(description = "New cell status (always SUBMITTED right after push)", example = "SUBMITTED")
    private String status;

    @Schema(description = "Market product id returned by the platform", example = "123456789")
    private String platformProductId;

    @Schema(description = "FEATURE_2610_34 / D9 · D18: one line for the success notice when a new Coupang registration"
            + " passed without 총 수량; null otherwise", example = "이 카테고리는 총 수량 없이 등록했습니다")
    private String grandTotalNotice;
}
