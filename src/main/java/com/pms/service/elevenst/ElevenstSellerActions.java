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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The 11st seller actions reopened by FEATURE_2610_17 / D60 — every one of them is a user-pressed button and cannot
 * be undone on 11st, so nothing here retries and the sync never calls it. A rejected call throws 502 with 11st's own
 * wording ({@link ElevenstRejectedException}).
 *
 * <ul>
 *   <li>1638 sale refusal (D61) · 1745 seller-fault cancel refusal (D62) — reason text EUC-KR in the path (D39 ②)</li>
 *   <li>6728 shipping-delay notice (D63) — POST XML</li>
 *   <li>1636 partial shipping · 6791 one line over several invoices (D64)</li>
 *   <li>7052 visit pickup done (D66) · 1773 reserved-sale stock arrived (D67)</li>
 * </ul>
 * Delivery method is always {@value #DELIVERY_METHOD_PARCEL} (parcel, D2 · D38 · D62).
 */
@Component
@RequiredArgsConstructor
public class ElevenstSellerActions {

    static final String DELIVERY_METHOD_PARCEL = "01";
    /** {@code ResultOrder} success code (1638 · 1745 · 6728 · 1636 · 6791 · 1773). */
    static final Set<String> RESULT_OK = Set.of("0");
    /** 1773 also answers −1 「이미 입고완료 처리 되었거나, 입고완료 처리할 건이 없습니다.」 — counted as done (D67). */
    static final Set<String> STOCK_CONFIRM_OK = Set.of("0", "-1");
    /** 7052 success code (D66 — {@code ns2:result_code} 1). */
    static final Set<String> VISIT_PICKUP_OK = Set.of("1");
    /** 6791 {@code invcAddData}: {@code invoice@quantity} joined by {@code ^} (D64). */
    private static final String SPLIT_PAIR = "@";
    private static final String SPLIT_JOIN = "^";

    private final ElevenstApiClient client;

    /** One invoice of a 6791 split: invoice number and the quantity it carries. */
    public record SplitInvoice(String invoiceNumber, int quantity) {
    }

    /** 11st's own {@code result_code} · {@code result_text} of an accepted call (kept in the cancel history, D70). */
    public record Outcome(String code, String text) {
    }

    /** 1638 — the whole order line is cancelled by the seller (no quantity field, D61). */
    public Outcome rejectOrder(MarketplaceAccount account, ElevenstOrderLine line, String reasonCode,
                               String reasonText) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ordNo", ElevenstText.pathSegment(line.getOrdNo()));
        values.put("ordPrdSeq", ElevenstText.pathSegment(line.getOrdPrdSeq()));
        values.put("ordCnRsnCd", reasonCode);
        values.put("ordCnDtlsRsn", ElevenstText.pathSegment(reasonText));
        byte[] raw = client.getPreEncoded(ElevenstPaths.fill(ElevenstPaths.REJECT_ORDER, values), account);
        String code = ElevenstOrderApi.requireResult(raw, RESULT_OK);
        return new Outcome(code, ElevenstXml.firstText(ElevenstXml.parseOrNull(raw), "result_text"));
    }

    /** 1745 — refuse a seller-fault cancel request; the delivery fields are always sent (D62 · D38). */
    public void rejectCancelFault(MarketplaceAccount account, ElevenstCancelRequest claim, String carrierCode,
                                  String invoiceNumber, LocalDate sentOn, String refuseCode, String reasonText) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ordNo", ElevenstText.pathSegment(claim.getOrdNo()));
        values.put("ordPrdSeq", ElevenstText.pathSegment(claim.getOrdPrdSeq()));
        values.put("ordPrdCnSeq", ElevenstText.pathSegment(claim.getOrdPrdCnSeq()));
        values.put("dlvMthdCd", DELIVERY_METHOD_PARCEL);
        values.put("sendDt", ElevenstTimes.DAY.format(sentOn));
        values.put("dlvEtprsCd", ElevenstText.pathSegment(carrierCode));
        values.put("invcNo", ElevenstText.pathSegment(invoiceNumber));
        values.put("ordCnRefsRsnCd", refuseCode);
        values.put("ordCnReqRsn", ElevenstText.pathSegment(reasonText));
        ElevenstOrderApi.requireResult(client.getPreEncoded(
                ElevenstPaths.fill(ElevenstPaths.CANCEL_REJECT_FAULT, values), account), RESULT_OK);
    }

    /** 6728 — the expected shipping date goes as {@code yyyyMMdd} (D63). */
    public void deliveryDelay(MarketplaceAccount account, String ordNo, String dlvNo, LocalDate expectedOn,
                              String reasonCode, String reasonText) {
        StringBuilder body = new StringBuilder("<DeliveryDelayGuideRequest>");
        ElevenstXml.element(body, "ordNo", ordNo);
        ElevenstXml.element(body, "dlvNo", dlvNo);
        ElevenstXml.element(body, "delaySendDt", ElevenstTimes.DAY.format(expectedOn));
        ElevenstXml.element(body, "delaySendRsnCd", reasonCode);
        ElevenstXml.element(body, "delaySendRsn", reasonText);
        body.append("</DeliveryDelayGuideRequest>");
        ElevenstOrderApi.requireResult(client.post(ElevenstPaths.DELIVERY_DELAY, body.toString(), account),
                RESULT_OK);
    }

    /** 1636 — only the named lines of the delivery are shipped; {@code ordPrdSeq} is comma-joined (D64). */
    public void shipPartial(MarketplaceAccount account, String dlvNo, String ordNo, List<String> ordPrdSeqs,
                            String carrierCode, String invoiceNumber, LocalDateTime sentAt) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sendDt", ElevenstTimes.MINUTE.format(sentAt));
        values.put("dlvMthdCd", DELIVERY_METHOD_PARCEL);
        values.put("dlvEtprsCd", carrierCode);
        values.put("invcNo", invoiceNumber);
        values.put("dlvNo", dlvNo);
        values.put("partDlvYn", "Y");
        values.put("ordNo", ordNo);
        values.put("ordPrdSeq", String.join(",", ordPrdSeqs));
        ElevenstOrderApi.requireResult(client.get(ElevenstPaths.fill(ElevenstPaths.SHIP_PARTIAL, values), account),
                RESULT_OK);
    }

    /** 6791 — one line over several invoices: the first in {@code invcNo · dlvQty}, the rest in {@code invcAddData}. */
    public void shipSplit(MarketplaceAccount account, String dlvNo, String ordNo, String ordPrdSeq, String carrierCode,
                          List<SplitInvoice> invoices, LocalDateTime sentAt) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sendDt", ElevenstTimes.MINUTE.format(sentAt));
        values.put("dlvMthdCd", DELIVERY_METHOD_PARCEL);
        values.put("dlvEtprsCd", carrierCode);
        values.put("invcNo", invoices.get(0).invoiceNumber());
        values.put("dlvNo", dlvNo);
        values.put("dlvQty", String.valueOf(invoices.get(0).quantity()));
        values.put("ordNo", ordNo);
        values.put("ordPrdSeq", ordPrdSeq);
        values.put("invcAddData", ElevenstText.pathSegment(invoices.subList(1, invoices.size()).stream()
                .map(i -> i.invoiceNumber() + SPLIT_PAIR + i.quantity())
                .collect(Collectors.joining(SPLIT_JOIN))));
        ElevenstOrderApi.requireResult(
                client.getPreEncoded(ElevenstPaths.fill(ElevenstPaths.SHIP_SPLIT, values), account), RESULT_OK);
    }

    /** 7052 — shipped and delivered in one call; the code is sent as the buyer gave it, trimmed only (D66). */
    public void visitPickup(MarketplaceAccount account, String dlvNo, String ordNo, String certCode) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("dlvNo", ElevenstText.pathSegment(dlvNo));
        values.put("ordNo", ElevenstText.pathSegment(ordNo));
        values.put("visitDlvCertCode", ElevenstText.pathSegment(certCode));
        byte[] raw = client.postPreEncoded(ElevenstPaths.fill(ElevenstPaths.VISIT_PICKUP, values), account);
        Document doc = ElevenstXml.parseOrNull(raw);
        String code = ElevenstXml.firstText(doc, "result_code");
        if (code == null || !VISIT_PICKUP_OK.contains(code)) {
            String text = ElevenstXml.firstText(doc, "result_text");
            throw new ElevenstRejectedException(
                    (text == null || text.isBlank()) ? ElevenstXml.messageOf(raw) : text, code, text);
        }
    }

    /** 1773 — reserved-sale stock arrived; −1 「이미 입고완료 처리 …」 counts as done (D67). */
    public void stockConfirm(MarketplaceAccount account, ElevenstOrderLine line) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ordNo", line.getOrdNo());
        values.put("ordPrdSeq", line.getOrdPrdSeq());
        ElevenstOrderApi.requireResult(client.get(ElevenstPaths.fill(ElevenstPaths.STOCK_CONFIRM, values), account),
                STOCK_CONFIRM_OK);
    }
}
