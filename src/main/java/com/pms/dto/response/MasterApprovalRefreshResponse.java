package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Result of the master-wide approval refresh (FEATURE_2610_38 / D10, POST /api/admin/master-products/{id}/fetch-status):
 * four counts plus one line per received listing id, in the order received.
 */
@Getter
@Builder
@Schema(description = "Master-wide approval refresh summary + one line per picked listing")
public class MasterApprovalRefreshResponse {

    @Schema(description = "How many listing ids were received", example = "3")
    private int requested;

    @Schema(description = "How many listings were refreshed", example = "1")
    private int refreshed;

    @Schema(description = "How many listings were skipped without a market call", example = "1")
    private int skipped;

    @Schema(description = "How many listing refreshes failed (isolated)", example = "1")
    private int failed;

    @Schema(description = "One line per received listing id, in the order received")
    private List<Result> results;

    /** REFRESHED · SKIPPED · FAILED. */
    public enum Outcome {
        REFRESHED,
        SKIPPED,
        FAILED
    }

    @Getter
    @Builder
    @Schema(description = "One listing's outcome")
    public static class Result {

        @Schema(description = "Product listing id", example = "42")
        private Long productListingId;

        @Schema(description = "Platform of the listing", example = "COUPANG")
        private String platform;

        @Schema(description = "REFRESHED · SKIPPED · FAILED", example = "REFRESHED")
        private Outcome outcome;

        /** REFRESHED only: the listing status after the refresh. */
        @Schema(description = "Listing status after the refresh (REFRESHED only)", example = "SELLING")
        private String status;

        /** REFRESHED only: the review reason read on this refresh (2609_74/D9 — never stored). */
        @Schema(description = "Review reason read on this refresh (REFRESHED only, null when none)",
                example = "상품정보제공고시 누락")
        private String reviewNote;

        /** REFRESHED only: FOUND / NOT_FOUND / FAILED, null = not looked up. */
        @Schema(description = "Outcome of the review-reason lookup (REFRESHED only)", example = "FOUND")
        private String reviewNoteState;

        /** SKIPPED · FAILED only: why it was skipped, or the server · market message of the failure. */
        @Schema(description = "Skip reason or failure message (SKIPPED · FAILED only)",
                example = "새로고침 대상 상태가 아님")
        private String message;
    }
}
