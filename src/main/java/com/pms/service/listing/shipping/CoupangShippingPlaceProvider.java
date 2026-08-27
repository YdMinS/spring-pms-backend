package com.pms.service.listing.shipping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.MarketplaceAccount;
import com.pms.service.coupang.CoupangApiClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Coupang {@link ShippingPlaceProvider} implementation (FEATURE_2608_06 / 72) — reuses {@link CoupangApiClient}
 * (account HMAC) for both calls; no new client method needed.
 *
 * <p>⚠️ The exact request paths and response field names must be confirmed against Coupang docs; the keys below
 * are validated against the local {@link com.pms.service.coupang.MockCoupangApiClient} fixture — real-account
 * hardening is follow-up. A parse failure or empty response yields an <b>empty list</b> (never an exception),
 * so the front falls back to manual entry — the same posture as {@code CoupangCategoryLookup.predict}.</p>
 */
@Component
@RequiredArgsConstructor
public class CoupangShippingPlaceProvider implements ShippingPlaceProvider {

    private static final Logger log = LoggerFactory.getLogger(CoupangShippingPlaceProvider.class);

    private static final String OUTBOUND_CENTERS =
            "/v2/providers/openapi/apis/api/v4/vendors/%s/outboundShippingCenters";
    private static final String RETURN_CENTERS =
            "/v2/providers/openapi/apis/api/v4/vendors/%s/returnShippingCenters";

    private final CoupangApiClient client;
    private final ObjectMapper objectMapper;

    @Override
    public String platform() {
        return "COUPANG";
    }

    @Override
    public List<OutboundPlace> fetchOutboundPlaces(MarketplaceAccount account) {
        JsonNode content = fetchContent(String.format(OUTBOUND_CENTERS, account.getVendorId()), account, "outbound");
        List<OutboundPlace> places = new ArrayList<>();
        for (JsonNode node : content) {
            places.add(new OutboundPlace(
                    asText(node, "outboundShippingPlaceCode"),
                    asText(node, "shippingPlaceName")));
        }
        return places;
    }

    @Override
    public List<ReturnCenter> fetchReturnCenters(MarketplaceAccount account) {
        JsonNode content = fetchContent(String.format(RETURN_CENTERS, account.getVendorId()), account, "return");
        List<ReturnCenter> centers = new ArrayList<>();
        for (JsonNode node : content) {
            JsonNode addr = node.path("placeAddresses").path(0);   // first address block
            centers.add(new ReturnCenter(
                    asText(node, "returnCenterCode"),
                    asText(node, "shippingPlaceName"),
                    asText(addr, "companyContactName"),
                    asText(addr, "companyContactNumber"),
                    asText(addr, "returnZipCode"),
                    asText(addr, "returnAddress"),
                    asText(addr, "returnAddressDetail"),
                    asDecimal(node, "returnFee"),
                    asDecimal(node, "deliveryFee")));
        }
        return centers;
    }

    /** GET the path, return the {@code data.content[]} array node (empty node on any failure). */
    private JsonNode fetchContent(String path, MarketplaceAccount account, String label) {
        try {
            String raw = client.get(path, "", account);
            return objectMapper.readTree(raw).path("data").path("content");
        } catch (Exception e) {
            // Parse / call failure → empty list (manual entry applies), never fail the caller.
            log.warn("[COUPANG-SHIPPING] {} fetch/parse failed: {}", label, e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    private static String asText(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static BigDecimal asDecimal(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : new BigDecimal(v.asText());
    }
}
