package com.pms.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/** ESM channel shipping settings (FEATURE_2610_26 / D36). Nothing stored yet = empty map (200, not 404). */
@Getter
@Builder(toBuilder = true)
public class EsmShippingConfigResponse {

    private Long marketplaceAccountId;
    private Map<String, String> settings;
    /** D65: ESM cells of this channel whose re-price failed after the save (0 on a read). */
    private int priceRecalcFailedCount;
}
