package com.pms.dto.response;

import com.pms.service.listing.shipping.OutboundPlace;

/**
 * Response for one outbound shipping place (FEATURE_2608_06 / 72) — same fields as the normalized
 * {@link OutboundPlace}. Built via {@link #from} (mirrors {@code CategoryNodeResponse.from}).
 */
public record OutboundPlaceResponse(String code, String name) {

    public static OutboundPlaceResponse from(OutboundPlace place) {
        return new OutboundPlaceResponse(place.code(), place.name());
    }
}
