package com.pms.service.listing.shipping;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.service.esm.EsmShippingLookups;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ESM shipping places (post 17) and return / exchange addresses (post 16 address book) — FEATURE_2610_26 / D37. The
 * seller registers them on ESM+; we only list them so a number can be picked. A failed or empty fetch yields an empty
 * list — the same posture as the Coupang and 11st providers.
 */
@Component
@RequiredArgsConstructor
public class EsmShippingPlaceProvider implements ShippingPlaceProvider {

    private static final Logger log = LoggerFactory.getLogger(EsmShippingPlaceProvider.class);

    private final EsmShippingLookups lookups;

    @Override
    public Platform platform() {
        return Platform.ESM;
    }

    /** D37: name = {@code placeName}. */
    @Override
    public List<OutboundPlace> fetchOutboundPlaces(MarketplaceAccount account) {
        List<OutboundPlace> places = new ArrayList<>();
        try {
            for (JsonNode place : lookups.shippingPlaces(account)) {
                places.add(new OutboundPlace(place.path("placeNo").asText(), place.path("placeName").asText()));
            }
        } catch (Exception e) {
            log.warn("[ESM-SHIPPING] shipping places fetch failed: {}", e.getMessage());
            return List.of();
        }
        return places;
    }

    /** D37: name = 「{addrName} ({addr1} {addr2})」 — the 11st provider's 「name (address)」 shape. */
    @Override
    public List<ReturnCenter> fetchReturnCenters(MarketplaceAccount account) {
        List<ReturnCenter> centers = new ArrayList<>();
        try {
            for (JsonNode address : lookups.addresses(account)) {
                String addr1 = address.path("addr1").asText("");
                String addr2 = address.path("addr2").asText("");
                String mobile = address.path("cellPhone").asText("");
                centers.add(new ReturnCenter(
                        address.path("addrNo").asText(),
                        displayName(address.path("addrName").asText(""), (addr1 + " " + addr2).trim()),
                        address.path("representativeName").asText(""),
                        mobile.isBlank() ? address.path("homeTel").asText("") : mobile,
                        address.path("zipCode").asText(""),
                        addr1,
                        addr2,
                        null,                                          // ESM return fee lives in esm.returnFee
                        null));
            }
        } catch (Exception e) {
            log.warn("[ESM-SHIPPING] address book fetch failed: {}", e.getMessage());
            return List.of();
        }
        return centers;
    }

    private static String displayName(String name, String address) {
        if (name.isBlank()) {
            return address;
        }
        return address.isBlank() ? name : name + " (" + address + ")";
    }
}
