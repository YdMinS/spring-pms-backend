package com.pms.service.listing.shipping;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LotteON outbound and return places (150) — FEATURE_2610_24 / D43. The seller registers them in the LotteON seller
 * center; we only list them so a number can be picked. A failed or empty fetch yields an empty list — the same posture
 * as the Coupang, 11st and ESM providers.
 */
@Component
@RequiredArgsConstructor
public class LotteonShippingPlaceProvider implements ShippingPlaceProvider {

    private static final Logger log = LoggerFactory.getLogger(LotteonShippingPlaceProvider.class);

    private final LotteonShippingLookups lookups;

    @Override
    public Platform platform() {
        return Platform.LOTTEON;
    }

    /** D43: type 02 places; name = 「{dvpNm} ({address})」 — the ESM · 11st providers' 「name (address)」 shape. */
    @Override
    public List<OutboundPlace> fetchOutboundPlaces(MarketplaceAccount account) {
        List<OutboundPlace> places = new ArrayList<>();
        try {
            for (JsonNode place : lookups.places(account, LotteonShippingLookups.OUTBOUND_PLACE)) {
                places.add(new OutboundPlace(place.path("dvpNo").asText(), displayName(place)));
            }
        } catch (Exception e) {
            log.warn("[LOTTEON-SHIPPING] outbound places fetch failed: {}", e.getMessage());
            return List.of();
        }
        return places;
    }

    /** D43: type 01 places; the address block is the road-name one. */
    @Override
    public List<ReturnCenter> fetchReturnCenters(MarketplaceAccount account) {
        List<ReturnCenter> centers = new ArrayList<>();
        try {
            for (JsonNode place : lookups.places(account, LotteonShippingLookups.RETURN_PLACE)) {
                String mobile = place.path("mphnNo").asText("");
                centers.add(new ReturnCenter(
                        place.path("dvpNo").asText(),
                        displayName(place),
                        place.path("rpbtrNm").asText(""),
                        mobile.isBlank() ? place.path("telNo").asText("") : mobile,
                        place.path("stnmZipNo").asText(""),
                        place.path("stnmZipAddr").asText(""),
                        place.path("stnmDtlAddr").asText(""),
                        null,                                          // LotteON return fee lives in the policy
                        null));
            }
        } catch (Exception e) {
            log.warn("[LOTTEON-SHIPPING] return places fetch failed: {}", e.getMessage());
            return List.of();
        }
        return centers;
    }

    private static String displayName(JsonNode place) {
        String name = place.path("dvpNm").asText("");
        String address = (place.path("stnmZipAddr").asText("") + " " + place.path("stnmDtlAddr").asText("")).trim();
        if (name.isBlank()) {
            return address;
        }
        return address.isBlank() ? name : name + " (" + address + ")";
    }
}
