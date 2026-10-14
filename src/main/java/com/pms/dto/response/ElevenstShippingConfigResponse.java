package com.pms.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/** 11st channel shipping settings (FEATURE_2610_13 / D84). Nothing stored yet = empty map (200, not 404). */
@Getter
@Builder(toBuilder = true)
public class ElevenstShippingConfigResponse {

    private Long marketplaceAccountId;
    private Map<String, String> settings;
    /** D120: 11st cells of this channel whose re-price failed after the save (0 on a read). */
    private int priceRecalcFailedCount;
}
