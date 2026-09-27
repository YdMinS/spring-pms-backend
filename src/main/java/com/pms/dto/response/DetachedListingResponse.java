package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * One detached (master-less) listing of an account — a candidate to re-attach through the import path
 * (2609_74/D1·D14). Read-only projection.
 */
@Getter
@Builder
@Schema(description = "Detached (master-less) listing of one account — candidate to re-attach")
public class DetachedListingResponse {

    @Schema(description = "Product listing (cell) id", example = "41")
    private Long productListingId;

    @Schema(description = "Marketplace product id (Coupang sellerProductId)", example = "123456789")
    private String platformProductId;

    @Schema(description = "Listing name", example = "생수 500ml")
    private String name;

    @Schema(description = "Listing status", example = "SELLING")
    private String status;
}
