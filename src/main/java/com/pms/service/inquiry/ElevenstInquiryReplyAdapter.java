package com.pms.service.inquiry;

import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.exception.BusinessException;
import com.pms.service.elevenst.ElevenstApiClient;
import com.pms.service.elevenst.ElevenstPaths;
import com.pms.service.elevenst.ElevenstSellerChoices;
import com.pms.service.elevenst.ElevenstXml;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import java.util.Map;

/**
 * 11st inquiry answers (FEATURE_2610_17 / D16 · D27 · D44 · D45 · D68).
 *
 * <ul>
 *   <li>Product Q&A: PUT 1885 {@code <ProductQna><answerCont>…</answerCont></ProductQna>} — success
 *       {@code resultCode} 200.</li>
 *   <li>Urgent notice: PUT 6982 {@code <request><confirmYn>Y</confirmYn><emerNtceSeq>…</emerNtceSeq>
 *       <answerCtnt>…</answerCtnt></request>} — success {@code result_code} 200.</li>
 *   <li>Non-delivery report: PUT 6734 {@code <NonDelivery><nonDlvSeq>…</nonDlvSeq><sellerDtlsRsn>…</sellerDtlsRsn>
 *       <sellerDtlsCont>…</sellerDtlsCont>} + for reason 102 {@code <dlvMthdCd>01</dlvMthdCd><dlvEtprsCd>…</dlvEtprsCd>
 *       <invcNo>…</invcNo>} {@code </NonDelivery>} — success {@code resultCode} 0, failure text {@code resultMessage}
 *       (FEATURE_2610_17 / D68).</li>
 * </ul>
 * Any other result → {@link InquiryReplyFailedException} with 11st's text (400). The dev / local stop (503, D8) is a
 * call that never left, so it is reported the same way with its own wording. Local DB is untouched here.
 */
@Component
@RequiredArgsConstructor
public class ElevenstInquiryReplyAdapter implements InquiryReplyAdapter {

    private static final String SUCCESS = "200";
    /** 6734 success, also for a report that was already handled (D68). */
    private static final String NON_DELIVERY_SUCCESS = "0";
    private static final String DELIVERY_METHOD_PARCEL = "01";

    private final ElevenstApiClient client;

    @Override
    public Platform platform() {
        return Platform.ELEVENST;
    }

    @Override
    public void reply(MarketplaceAccount account, CustomerInquiry inquiry, ReplyCommand command) {
        if (inquiry.getInquiryType() == InquiryType.NON_DELIVERY) {
            nonDelivery(account, inquiry, command);
            return;
        }
        byte[] raw;
        String codeField;
        try {
            if (inquiry.getInquiryType() == InquiryType.CALL_CENTER) {
                StringBuilder body = new StringBuilder("<request>");
                ElevenstXml.element(body, "confirmYn", "Y");
                ElevenstXml.element(body, "emerNtceSeq", inquiry.getExternalInquiryId());
                ElevenstXml.element(body, "answerCtnt", command.content());
                body.append("</request>");
                raw = client.put(ElevenstPaths.ALIMI_ANSWER, body.toString(), account);
                codeField = "result_code";
            } else {
                StringBuilder body = new StringBuilder("<ProductQna>");
                ElevenstXml.element(body, "answerCont", command.content());
                body.append("</ProductQna>");
                raw = client.put(ElevenstPaths.fill(ElevenstPaths.QNA_ANSWER, Map.of(
                        "brdInfoNo", inquiry.getExternalInquiryId(),
                        "prdNo", inquiry.getExternalProductId() == null ? "" : inquiry.getExternalProductId())),
                        body.toString(), account);
                codeField = "resultCode";
            }
        } catch (BusinessException e) {
            if (e.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
                throw new InquiryReplyFailedException(e.getMessage());
            }
            throw e;
        }
        Document doc = ElevenstXml.parseOrNull(raw);
        if (!SUCCESS.equals(ElevenstXml.firstText(doc, codeField))) {
            String text = ElevenstXml.firstText(doc, "result_text");
            throw new InquiryReplyFailedException(text != null && !text.isBlank() ? text : ElevenstXml.messageOf(raw));
        }
    }

    /** 6734 for one non-delivery report; the carrier and invoice go only with reason 102 (D68). */
    private void nonDelivery(MarketplaceAccount account, CustomerInquiry inquiry, ReplyCommand command) {
        StringBuilder body = new StringBuilder("<NonDelivery>");
        ElevenstXml.element(body, "nonDlvSeq", inquiry.getExternalInquiryId());
        ElevenstXml.element(body, "sellerDtlsRsn", command.reasonCode());
        ElevenstXml.element(body, "sellerDtlsCont", command.content());
        if (ElevenstSellerChoices.RESEND.equals(command.reasonCode())) {
            ElevenstXml.element(body, "dlvMthdCd", DELIVERY_METHOD_PARCEL);
            ElevenstXml.element(body, "dlvEtprsCd", command.carrierCode());
            ElevenstXml.element(body, "invcNo", command.invoiceNumber());
        }
        body.append("</NonDelivery>");
        byte[] raw;
        try {
            raw = client.put(ElevenstPaths.fill(ElevenstPaths.NON_DELIVERY_PROCESS, Map.of(
                    "ordNo", inquiry.getExternalOrderId() == null ? "" : inquiry.getExternalOrderId(),
                    "ordPrdSeq", inquiry.getExternalItemId() == null ? "" : inquiry.getExternalItemId())),
                    body.toString(), account);
        } catch (BusinessException e) {
            if (e.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
                throw new InquiryReplyFailedException(e.getMessage());
            }
            throw e;
        }
        Document doc = ElevenstXml.parseOrNull(raw);
        if (!NON_DELIVERY_SUCCESS.equals(ElevenstXml.firstText(doc, "resultCode"))) {
            String text = ElevenstXml.firstText(doc, "resultMessage");
            throw new InquiryReplyFailedException(text != null && !text.isBlank() ? text : ElevenstXml.messageOf(raw));
        }
    }
}
