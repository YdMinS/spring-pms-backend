package com.pms.dto.response;

import com.pms.domain.GeneratedContentSource;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
    /** Origin of the thumbnail (25): AUTO = renderer output, MANUAL_OVERRIDE = user-uploaded image. */
    private GeneratedContentSource thumbnailSource;
    /** This cell's channel-level field-value overrides (FEATURE_2608_06 / 12); null override → empty map. */
    private Map<String, String> fieldValues;
    /** This cell's channel raw tags (33); null → empty list. */
    private List<String> tags;
    private List<OptionPrice> optionPrices;

    @Getter
    @Builder
    public static class OptionPrice {
        private Long optionId;
        /** Channel option display name (matches MasterProductOption.name); lets the UI show the name
         *  without resolving optionId against the master's option list (different id space). */
        private String optionName;
        private BigDecimal sellingPrice;
        /** Per-channel active flag (42): only active options are pushed to the market. */
        private Boolean active;
    }
}
