package com.pms.dto.response;

import com.pms.domain.ProductListing;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The 「마켓 값 사용」 flags of a channel cell after [마스터 값으로 바꾸기] (FEATURE_2611_27 / D20 · D27).
 *
 * <p>Nothing is sent to the market.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Market-value flags of a channel cell")
public class ListingMarketValuesResponse {

    @Schema(description = "Product listing ID", example = "12")
    private Long productListingId;

    @Schema(description = "The attributes and notices of this cell use the values read from the market", example = "false")
    private boolean usesMarketAttributes;

    @Schema(description = "The search tags of this cell use the values read from the market", example = "false")
    private boolean usesMarketTags;

    @Schema(description = "The shipping settings of this cell use the values read from the market", example = "false")
    private boolean usesMarketShipping;

    public static ListingMarketValuesResponse of(ProductListing cell) {
        return ListingMarketValuesResponse.builder()
                .productListingId(cell.getId())
                .usesMarketAttributes(cell.isUsesMarketAttributes())
                .usesMarketTags(cell.isUsesMarketTags())
                .usesMarketShipping(cell.isUsesMarketShipping())
                .build();
    }
}
