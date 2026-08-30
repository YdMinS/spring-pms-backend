package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * One pending-market-sync cell (FEATURE_2608_06 / 3d, GET /api/admin/listings/pending-sync): a listing
 * regenerated locally (layer A) but not yet pushed to the market.
 */
@Getter
@Builder
@Schema(description = "A cell awaiting market push (needs_market_sync = true)")
public class PendingSyncResponse {

    @Schema(description = "Product listing id (the cell to push)", example = "42")
    private Long productListingId;

    @Schema(description = "Name of the master product this cell belongs to", example = "운동화 마스터")
    private String masterProductName;

    @Schema(description = "Seller name", example = "행복상회")
    private String seller;

    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @Schema(description = "Current listing status", example = "SELLING")
    private String status;
}
