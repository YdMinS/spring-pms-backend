package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Read-only preview of what [채널에 반영하기] would change (FEATURE_2608_06 / 89,
 * GET /api/admin/master-products/{id}/channel-sync-preview).
 *
 * <p><b>Core rule:</b> {@code inSync} and {@link Totals} count only the differences that a propagation run
 * actually removes. A difference propagation leaves in place (a market-registered orphan, a cell with no
 * generated assets) would keep the banner up and the button lit forever, so it is never counted:</p>
 * <ul>
 *   <li>{@code marketOrphanOptions} — reported for the operator (WING must switch them off by hand) but
 *       excluded from {@code totals}/{@code affectedChannels}/{@code inSync}.</li>
 *   <li>Cells with no {@code GeneratedProductData} are omitted from {@code channels} entirely — propagation
 *       counts them as {@code skipped}.</li>
 * </ul>
 */
@Getter
@Builder
@Schema(description = "Master ↔ channel difference preview (read-only)")
public class ChannelSyncPreviewResponse {

    @Schema(description = "No difference that a propagation run could remove", example = "false")
    private boolean inSync;

    @Schema(description = "Option-count totals across all channels")
    private Totals totals;

    @Schema(description = "Only the channels that have at least one difference")
    private List<Channel> channels;

    /** Option-count sums (NOT channel counts), except {@code affectedChannels}. */
    @Getter
    @Builder
    @Schema(description = "Difference totals across channels")
    public static class Totals {

        @Schema(description = "Cells with at least one fixable difference (market-only orphans excluded)",
                example = "2")
        private int affectedChannels;

        @Schema(description = "Total options the master has and a cell lacks", example = "3")
        private int missingOptions;

        @Schema(description = "Total active orphan options a propagation would switch off", example = "1")
        private int orphanOptions;

        @Schema(description = "Total options whose BOM quantities differ from the master", example = "2")
        private int quantityMismatch;
    }

    /** One channel cell and its differences (empty lists are kept for a stable response shape). */
    @Getter
    @Builder
    @Schema(description = "Per-channel difference detail")
    public static class Channel {

        @Schema(description = "ProductListing (cell) id", example = "100")
        private Long listingId;

        @Schema(description = "Seller name", example = "행복상회")
        private String sellerName;

        @Schema(description = "Platform", example = "COUPANG")
        private String platform;

        @Schema(description = "Registered on the market (platformProductId != null)", example = "true")
        private boolean onMarket;

        @Schema(description = "In the master, absent from this cell → propagation creates them (switched off)")
        private List<String> missingOptions;

        @Schema(description = "Active on this cell, gone from the master → propagation switches them off")
        private List<String> orphanOptions;

        @Schema(description = "Active orphans on a market-registered cell → propagation leaves them; "
                + "the operator must stop them on the marketplace (informational only)")
        private List<String> marketOrphanOptions;

        @Schema(description = "Matched options whose shared-product BOM quantities differ from the master")
        private List<String> quantityMismatchOptions;
    }
}
