package com.pms.dto.response;

import com.pms.domain.MasterProductOption;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.service.listing.ListingStockPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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

    @Schema(description = "Auto-generated registration name (등록상품명) for this listing's current active options (67)",
            example = "노브랜드 생수 x 6")
    private String registrationName;

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

        @Schema(description = "Per-channel stock override (102); null = inherit the master option's stock. "
                + "Effective value = stockQuantity ?? maxStock (derived — not sent separately).",
                nullable = true, example = "30")
        private Integer stockQuantity;

        @Schema(description = "Upper bound for this option's channel stock = master stock ?? 9999 (102/D5); "
                + "also the effective value while stockQuantity is null", example = "50")
        private int maxStock;

        @Schema(description = "Origin of the selling price (2609_19/D1): AUTO = calculated, "
                + "MANUAL_OVERRIDE = set by hand for this channel. sellingPrice is the effective price either way.",
                example = "AUTO")
        private String priceSource;

        /** {@code master} may be null (renamed/legacy option that matches no master option) → maxStock 9999. */
        public static OptionItem from(ProductListingOption option, MasterProductOption master) {
            return OptionItem.builder()
                    .optionId(option.getId())
                    .optionName(option.getOptionName())
                    .sellingPrice(option.getSellingPrice())
                    .active(Boolean.TRUE.equals(option.getActive()))
                    .approvalStatus(option.getApprovalStatus() != null ? option.getApprovalStatus().name() : null)
                    .stockQuantity(option.getStockQuantity())
                    .maxStock(ListingStockPolicy.ceiling(master))
                    .priceSource(option.getPriceSource() != null ? option.getPriceSource().name() : null)
                    .build();
        }
    }

    /**
     * @param masterOptionsByName master options of this listing's master, keyed by name — the axis that
     *        resolves each option's stock ceiling (102). Empty map on a legacy cell without a master.
     *        Passed in (never queried here): a DTO mapper must not reach for a repository.
     */
    public static ListingOptionsResponse of(ProductListing listing, List<ProductListingOption> options,
                                            boolean needsResync, String registrationName,
                                            Map<String, MasterProductOption> masterOptionsByName) {
        return ListingOptionsResponse.builder()
                .productListingId(listing.getId())
                .status(listing.getStatus() != null ? listing.getStatus().name() : null)
                .needsResync(needsResync)
                .registrationName(registrationName)
                .options(options.stream()
                        .map(o -> OptionItem.from(o, masterOptionsByName.get(o.getOptionName())))
                        .toList())
                .build();
    }
}
