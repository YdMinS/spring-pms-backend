package com.pms.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/** LotteON channel shipping settings (FEATURE_2610_24 / D42). Nothing stored yet = empty map (200, not 404). */
@Getter
@Builder(toBuilder = true)
public class LotteonShippingConfigResponse {

    private Long marketplaceAccountId;
    private Map<String, String> settings;
    /** D66: LotteON cells of this channel whose re-price failed after the save (0 on a read). */
    private int priceRecalcFailedCount;
}
