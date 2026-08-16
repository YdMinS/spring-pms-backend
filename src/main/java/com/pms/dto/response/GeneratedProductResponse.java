package com.pms.dto.response;

import com.pms.domain.GeneratedContentSource;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Auto-generated assets for a channel cell (FEATURE_2608_06 / 3b-2): thumbnail URL, detail HTML, its
 * source (AUTO / MANUAL_OVERRIDE, Step 2-2), and the per-option selling prices computed by the margin
 * engine. Returned by regenerate/generated/detail-html endpoints.
 */
@Getter
@Builder
public class GeneratedProductResponse {

    private Long productListingId;
    private String thumbnailUrl;
    private String detailHtml;
    private GeneratedContentSource source;
    private List<OptionPrice> optionPrices;

    @Getter
    @Builder
    public static class OptionPrice {
        private Long optionId;
        private BigDecimal sellingPrice;
    }
}
