package com.pms.dto.response;

import com.pms.domain.ProductListingOption;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Response of the fetch-status (manual refresh) endpoint (FEATURE_2608_06 / 3c): the cell status plus each
 * option's approval state + market option id.
 */
@Getter
@Builder
@Schema(description = "Cell status + per-option approval after a manual refresh")
public class ListingStatusResponse {

    @Schema(description = "Product listing (cell) id", example = "1")
    private Long productListingId;

    @Schema(description = "Cell status after the refresh", example = "SELLING")
    private String status;

    @Schema(description = "Per-option approval state")
    private List<OptionStatus> options;

    /** 2609_74/D9: 심사 사유. 조회하지 않았거나 받지 못했으면 null. 저장되지 않는 값이다. */
    @Schema(description = "Review reason read from the market on this refresh (null when none)",
            example = "상품정보제공고시 누락")
    private String reviewNote;

    /** FOUND / NOT_FOUND / FAILED. null = 이 상태에서는 조회하지 않았다. */
    @Schema(description = "Outcome of the review-reason lookup (null = not looked up)", example = "FOUND")
    private String reviewNoteState;

    @Getter
    @Builder
    @Schema(description = "One option's approval state + market option id")
    public static class OptionStatus {

        @Schema(description = "Option id", example = "50")
        private Long optionId;

        @Schema(description = "Approval state (source of truth)", example = "APPROVED")
        private String approvalStatus;

        @Schema(description = "Platform option id (vendorItemId, order mapping)", example = "987654321")
        private String platformOptionId;

        public static OptionStatus from(ProductListingOption option) {
            return OptionStatus.builder()
                    .optionId(option.getId())
                    .approvalStatus(option.getApprovalStatus() != null ? option.getApprovalStatus().name() : null)
                    .platformOptionId(option.getPlatformOptionId())
                    .build();
        }
    }
}
