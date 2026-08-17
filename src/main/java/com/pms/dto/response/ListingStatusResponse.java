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
