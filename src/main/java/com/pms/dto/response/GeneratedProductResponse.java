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
    /** This cell's channel-level shipping overrides (75); null → empty map. */
    private Map<String, String> shippingOverride;
    /**
     * Whether this cell's resolved shipping config (channel ?? master ?? account, 75) satisfies the
     * channel's register requirements (77) — the UI guards [마켓 등록] with it. {@code null} when the
     * platform has no adapter yet (no opinion, not a failure) or on a legacy/not-yet-generated response.
     */
    private Boolean shippingReady;
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
        /** 87: the option exists on the marketplace, so the front locks its checkbox (it cannot be unchecked). */
        private Boolean onMarket;
        /** 102: per-channel stock override; null = inherit the master option's stock (effective = maxStock). */
        private Integer stockQuantity;
        /** 102/D5: upper bound for this option's channel stock = master stock ?? 9999; also the inherited value. */
        private int maxStock;
    }
}
