package com.pms.service.listing.shipping;

import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.service.elevenst.ElevenstApiClient;
import com.pms.service.elevenst.ElevenstXml;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * 11st outbound places (1014) / return-exchange places (1015) — FEATURE_2610_13 / D84. The seller registers the
 * addresses on the 11st seller office; we only list them so a number ({@code addrSeq}) can be picked.
 *
 * <p>Both lists are {@code inOutAddress{addrSeq, addrNm, addr, rcvrNm, gnrlTlphnNo, prtblTlphnNo}}. A failed or
 * empty fetch yields an empty list (manual entry then applies) — the same posture as the Coupang provider.</p>
 */
@Component
@RequiredArgsConstructor
public class ElevenstShippingPlaceProvider implements ShippingPlaceProvider {

    private static final Logger log = LoggerFactory.getLogger(ElevenstShippingPlaceProvider.class);

    static final String OUTBOUND_PATH = "/rest/areaservice/outboundarea";
    static final String INBOUND_PATH = "/rest/areaservice/inboundarea";

    private final ElevenstApiClient client;

    @Override
    public Platform platform() {
        return Platform.ELEVENST;
    }

    @Override
    public List<OutboundPlace> fetchOutboundPlaces(MarketplaceAccount account) {
        List<OutboundPlace> places = new ArrayList<>();
        for (Element address : fetch(OUTBOUND_PATH, account, "outbound")) {
            places.add(new OutboundPlace(ElevenstXml.firstText(address, "addrSeq"), displayName(address)));
        }
        return places;
    }

    @Override
    public List<ReturnCenter> fetchReturnCenters(MarketplaceAccount account) {
        List<ReturnCenter> centers = new ArrayList<>();
        for (Element address : fetch(INBOUND_PATH, account, "inbound")) {
            String mobile = ElevenstXml.firstText(address, "prtblTlphnNo");
            centers.add(new ReturnCenter(
                    ElevenstXml.firstText(address, "addrSeq"),
                    displayName(address),
                    ElevenstXml.firstText(address, "rcvrNm"),
                    mobile != null && !mobile.isBlank() ? mobile : ElevenstXml.firstText(address, "gnrlTlphnNo"),
                    null,                                          // the list carries no zip code
                    ElevenstXml.firstText(address, "addr"),
                    null,
                    null,                                          // 11st return fees live in the shipping fields
                    null));
        }
        return centers;
    }

    private List<Element> fetch(String path, MarketplaceAccount account, String label) {
        try {
            Document document = ElevenstXml.parseOrNull(client.get(path, account));
            return document == null ? List.of() : ElevenstXml.elements(document, "inOutAddress");
        } catch (Exception e) {
            log.warn("[ELEVENST-SHIPPING] {} fetch/parse failed: {}", label, e.getMessage());
            return List.of();
        }
    }

    /** 「name (address)」 when both exist — the number alone means nothing to a person picking it. */
    private static String displayName(Element address) {
        String name = ElevenstXml.firstText(address, "addrNm");
        String addr = ElevenstXml.firstText(address, "addr");
        if (name == null || name.isBlank()) {
            return addr;
        }
        return addr == null || addr.isBlank() ? name : name + " (" + addr + ")";
    }
}
