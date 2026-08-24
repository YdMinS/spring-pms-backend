package com.pms.dto.response;

import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response of the per-channel option endpoints (FEATURE_2608_06 / 42): the listing's full option set (active and
 * inactive) so the toggle UI can render everything, plus a listing-level {@code needsResync} flag.
 *
 * <p>{@code needsResync} is only meaningful on the PUT (set-active) response: it is {@code true} when any cell of
 * this listing is already pushed ({@code status != DRAFT}) — an active-set change alone does not reach the market,
 * so the front prompts a re-register/update. GET always returns {@code false} (a read is not a resync trigger).</p>
 */
@Getter
@Builder
@Schema(description = "Full option set for a channel listing + needsResync flag")
public class ListingOptionsResponse {

    @Schema(description = "Product listing (cell) id", example = "1")
    private Long productListingId;

    @Schema(description = "Cell status", example = "DRAFT")
    private String status;

    @Schema(description = "True when the active-set change needs a market re-push (cell already SUBMITTED/SELLING)",
            example = "false")
    private boolean needsResync;

    @Schema(description = "Every option of the listing (active + inactive)")
    private List<OptionItem> options;

    @Getter
    @Builder
    @Schema(description = "One option: id/name/price + active + approval state")
    public static class OptionItem {

        @Schema(description = "Option id", example = "50")
        private Long optionId;

        @Schema(description = "Option name/label", example = "Size M")
        private String optionName;

        @Schema(description = "Selling price", example = "12999.99")
        private BigDecimal sellingPrice;

        @Schema(description = "Active on this channel (included in the market payload)", example = "true")
        private boolean active;

        @Schema(description = "Approval state (source of truth)", example = "APPROVED")
        private String approvalStatus;

        public static OptionItem from(ProductListingOption option) {
            return OptionItem.builder()
                    .optionId(option.getId())
                    .optionName(option.getOptionName())
                    .sellingPrice(option.getSellingPrice())
                    .active(Boolean.TRUE.equals(option.getActive()))
                    .approvalStatus(option.getApprovalStatus() != null ? option.getApprovalStatus().name() : null)
                    .build();
        }
    }

    public static ListingOptionsResponse of(ProductListing listing, List<ProductListingOption> options,
                                            boolean needsResync) {
        return ListingOptionsResponse.builder()
                .productListingId(listing.getId())
                .status(listing.getStatus() != null ? listing.getStatus().name() : null)
                .needsResync(needsResync)
                .options(options.stream().map(OptionItem::from).toList())
                .build();
    }
}
