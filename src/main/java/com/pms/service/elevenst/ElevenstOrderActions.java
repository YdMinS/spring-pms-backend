package com.pms.service.elevenst;

import com.pms.domain.ElevenstCancelRequest;
import com.pms.domain.ElevenstOrderLine;
import com.pms.domain.MarketplaceAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The 11st order writes of this feature (FEATURE_2610_17): order confirmation 1634 (D20 · D48), shipping 1888 (D2),
 * cancel approve 1640 / reject 1641 (D19 · D37 · D38). All of them cannot be undone on 11st — callers are
 * user-pressed buttons only, never the sync. A rejected call throws 502 with 11st's own wording.
 *
 * <p>Delivery method is always {@value #DELIVERY_METHOD_PARCEL} (parcel, D2 · D38).
 */
@Component
@RequiredArgsConstructor
public class ElevenstOrderActions {

    /** {@code dlvMthdCd} 01 = parcel. */
    static final String DELIVERY_METHOD_PARCEL = "01";
    /** 1634 success: 0, and −3206 「이미 발주 처리 되었거나 발주 처리할 건이 없음」 counts as done (D48). */
    static final Set<String> ACKNOWLEDGE_OK = Set.of("0", "-3206");
    /** {@code ResultOrder} success code. */
    static final Set<String> RESULT_OK = Set.of("0");

    private final ElevenstApiClient client;

    /**
     * 1634 for one (delivery number, product number) group — 11st confirms the whole group at once (D48).
     *
     * @param anchor any order line of the group; its {@code raw} carries {@code addPrdYn} / {@code addPrdNo}
     */
    public void acknowledge(MarketplaceAccount account, ElevenstOrderLine anchor, String dlvNo) {
        String raw = anchor.getRaw() == null ? "" : anchor.getRaw();
        Document doc = ElevenstXml.parseOrNull(raw.getBytes(ElevenstXml.EUC_KR));
        String addPrdYn = valueOrNull(ElevenstXml.firstText(doc, "addPrdYn"));
        String addPrdNo = valueOrNull(ElevenstXml.firstText(doc, "addPrdNo"));
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ordNo", anchor.getOrdNo());
        values.put("ordPrdSeq", anchor.getOrdPrdSeq());
        values.put("addPrdYn", "null".equals(addPrdYn) ? "N" : addPrdYn);
        values.put("addPrdNo", addPrdNo);
        values.put("dlvNo", dlvNo);
        ElevenstOrderApi.requireResult(client.get(ElevenstPaths.fill(ElevenstPaths.ACKNOWLEDGE, values), account),
                ACKNOWLEDGE_OK);
    }

    /** 1888 for one delivery number — every order bundled under it is shipped by this one call (D2). */
    public void ship(MarketplaceAccount account, String dlvNo, String carrierCode, String invoiceNumber,
                     LocalDateTime sentAt) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sendDt", ElevenstTimes.MINUTE.format(sentAt));
        values.put("dlvMthdCd", DELIVERY_METHOD_PARCEL);
        values.put("dlvEtprsCd", carrierCode);
        values.put("invcNo", invoiceNumber);
        values.put("dlvNo", dlvNo);
        ElevenstOrderApi.requireResult(client.get(ElevenstPaths.fill(ElevenstPaths.SHIP, values), account), RESULT_OK);
    }

    /** 1640 cancel approve. */
    public void approveCancel(MarketplaceAccount account, ElevenstCancelRequest claim) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ordPrdCnSeq", claim.getOrdPrdCnSeq());
        values.put("ordNo", claim.getOrdNo());
        values.put("ordPrdSeq", claim.getOrdPrdSeq());
        ElevenstOrderApi.requireResult(
                client.get(ElevenstPaths.fill(ElevenstPaths.CANCEL_APPROVE, values), account), RESULT_OK);
    }

    /** 1641 cancel reject — 11st requires the invoice the goods left with (D38); there is no reason field. */
    public void rejectCancel(MarketplaceAccount account, ElevenstCancelRequest claim, String carrierCode,
                             String invoiceNumber, LocalDate sentOn) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ordNo", claim.getOrdNo());
        values.put("ordPrdSeq", claim.getOrdPrdSeq());
        values.put("ordPrdCnSeq", claim.getOrdPrdCnSeq());
        values.put("dlvMthdCd", DELIVERY_METHOD_PARCEL);
        values.put("sendDt", ElevenstTimes.DAY.format(sentOn));
        values.put("dlvEtprsCd", carrierCode);
        values.put("invcNo", invoiceNumber);
        ElevenstOrderApi.requireResult(
                client.get(ElevenstPaths.fill(ElevenstPaths.CANCEL_REJECT, values), account), RESULT_OK);
    }

    /** Guide rule: an absent path value is sent as the text {@code null}. */
    private static String valueOrNull(String value) {
        return (value == null || value.isBlank()) ? "null" : value.trim();
    }
}
