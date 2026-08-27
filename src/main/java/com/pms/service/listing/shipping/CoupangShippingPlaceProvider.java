package com.pms.service.listing.shipping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.MarketplaceAccount;
import com.pms.service.coupang.CoupangApiClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Coupang {@link ShippingPlaceProvider} implementation (FEATURE_2608_06 / 72, paths corrected 74-fix) —
 * reuses {@link CoupangApiClient} (account HMAC) for both calls; no new client method needed.
 *
 * <p>The two lookups use <b>different</b> Coupang gateways (confirmed empirically on a live account):
 * <ul>
 *   <li>반품지: {@code GET /v2/providers/openapi/apis/api/v4/vendors/{vendorId}/returnShippingCenters}
 *       — <b>confirmed working</b> (returns the list; picker shows). The docs list a v5 variant, but v5 returned
 *       an empty list on the live account, so v4 is retained until v5 is verified. No paging query.</li>
 *   <li>출고지: {@code GET /v2/providers/marketplace_openapi/apis/api/v2/vendor/shipping-place/outbound}
 *       — the WING 출고지(shipping place) endpoint (vendorId <b>not</b> in the path, query paging). The symmetric
 *       {@code openapi/v4/…/outboundShippingCenters} returned an empty list on the live account (it's a different
 *       resource), so we use the documented shipping-place endpoint. Response fields
 *       ({@code outboundShippingPlaceCode}, {@code shippingPlaceName}) match; defensive envelope parse below.</li>
 * </ul>
 *
 * <p>Return-center fees are <b>weight-tiered</b> ({@code returnFee02kg..20kg}); there is no single
 * {@code returnFee}/{@code deliveryFee} and no contact-name field — so charge/name are left null here and the
 * seller enters the register fees ({@code returnCharge}, {@code deliveryChargeOnReturn}) manually. A parse
 * failure or empty response yields an <b>empty list</b> (never an exception), so the front falls back to manual
 * entry — the same posture as {@code CoupangCategoryLookup.predict}.</p>
 */
@Component
@RequiredArgsConstructor
public class CoupangShippingPlaceProvider implements ShippingPlaceProvider {

    private static final Logger log = LoggerFactory.getLogger(CoupangShippingPlaceProvider.class);

    private static final String OUTBOUND_CENTERS =
            "/v2/providers/marketplace_openapi/apis/api/v2/vendor/shipping-place/outbound";
    private static final String RETURN_CENTERS =
            "/v2/providers/openapi/apis/api/v4/vendors/%s/returnShippingCenters";
    private static final String OUTBOUND_PAGING = "pageNum=1&pageSize=50";   // pageSize max = 50 per the docs

    private final CoupangApiClient client;
    private final ObjectMapper objectMapper;

    @Override
    public String platform() {
        return "COUPANG";
    }

    @Override
    public List<OutboundPlace> fetchOutboundPlaces(MarketplaceAccount account) {
        JsonNode content = fetchContent(OUTBOUND_CENTERS, OUTBOUND_PAGING, account, "outbound");
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
        // No paging query on return — the confirmed-working v4 call sends none.
        JsonNode content = fetchContent(String.format(RETURN_CENTERS, account.getVendorId()), "", account, "return");
        List<ReturnCenter> centers = new ArrayList<>();
        for (JsonNode node : content) {
            JsonNode addr = node.path("placeAddresses").path(0);   // first address block
            centers.add(new ReturnCenter(
                    asText(node, "returnCenterCode"),
                    asText(node, "shippingPlaceName"),
                    null,                                            // no contact-name field in the response
                    asText(addr, "companyContactNumber"),
                    asText(addr, "returnZipCode"),
                    asText(addr, "returnAddress"),
                    asText(addr, "returnAddressDetail"),
                    null,                                            // fees are weight-tiered → seller enters manually
                    null));
        }
        return centers;
    }

    /** GET the path (query = paging or ""), return the list array node (data.content ?? content ?? data). */
    private JsonNode fetchContent(String path, String query, MarketplaceAccount account, String label) {
        try {
            String raw = client.get(path, query, account);
            JsonNode root = objectMapper.readTree(raw);
            JsonNode content = root.path("data").path("content");
            if (!content.isArray()) {
                content = root.path("content");                      // some gateways omit the data envelope
            }
            if (!content.isArray()) {
                content = root.path("data");                          // or return data directly as the array
            }
            return content.isArray() ? content : objectMapper.createArrayNode();
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
}
