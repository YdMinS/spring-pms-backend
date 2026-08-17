package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Batch channel-add result (FEATURE_2608_06 / 15): per-target outcomes plus aggregate counts.
 *
 * <p>HTTP is always 200 — partial failure is reported in the body ({@code succeeded}/{@code failed} counts +
 * per-result {@code success}/{@code errorMessage}), not as an error status.</p>
 */
@Getter
@Builder
@Schema(description = "Batch channel-add result: per-target outcomes + aggregate counts")
public class BatchChannelAddResponse {

    @Schema(description = "Number of targets requested", example = "3")
    private int requested;

    @Schema(description = "Number of cells created", example = "2")
    private int succeeded;

    @Schema(description = "Number of targets that failed (duplicate, missing category, …)", example = "1")
    private int failed;

    @Schema(description = "Per-target outcome")
    private List<Result> results;

    @Getter
    @Builder
    @Schema(description = "Outcome for a single (seller, platform) target")
    public static class Result {

        @Schema(description = "Seller ID of this target", example = "1")
        private Long sellerId;

        @Schema(description = "Platform of this target", example = "COUPANG")
        private String platform;

        @Schema(description = "Whether the cell was created", example = "true")
        private boolean success;

        @Schema(description = "New listing id (present on success)", example = "10")
        private Long productListingId;

        @Schema(description = "Failure reason (present on failure)", example = "이미 등록된 채널")
        private String errorMessage;
    }
}
