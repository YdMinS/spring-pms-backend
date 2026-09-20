package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Every channel cell of one master product with its full option set (FEATURE_2609_61 / D6): one call instead of
 * a per-cell {@code GET /api/admin/product-listings/{id}/options}.
 *
 * <p>⚠️ The option payload is {@link ListingOptionsResponse.OptionItem} <b>reused as-is</b> — a second option DTO
 * would let the same option show different values on different screens.</p>
 *
 * <p>Channel labels (판매자·플랫폼·계정) are deliberately absent: the screen renders this together with
 * {@code GET /{id}/matrix}, which already carries them. Duplicating them here would let the two responses
 * drift apart.</p>
 */
@Getter
@Builder
@Schema(description = "All channel cells of a master product with their options (ids included)")
public class MasterChannelOptionsResponse {

    @Schema(description = "Master product id", example = "12")
    private Long masterId;

    @Schema(description = "Every cell linked to this master (empty when the master has none)")
    private List<CellOptions> cells;

    @Getter
    @Builder
    @Schema(description = "One channel cell + its options")
    public static class CellOptions {

        @Schema(description = "Product listing (cell) id", example = "41")
        private Long productListingId;

        @Schema(description = "상품 ID on the marketplace (Coupang sellerProductId); null = not on the market yet "
                + "(DRAFT) — not an error", nullable = true, example = "12345678")
        private String platformProductId;

        @Schema(description = "Cell status", example = "SELLING")
        private String status;

        @Schema(description = "Every option of the cell (active + inactive)")
        private List<ListingOptionsResponse.OptionItem> options;
    }
}
