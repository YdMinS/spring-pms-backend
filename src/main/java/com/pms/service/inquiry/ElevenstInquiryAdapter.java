package com.pms.service.inquiry;

import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.exception.BusinessException;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.elevenst.ElevenstApiClient;
import com.pms.service.elevenst.ElevenstOrderApi;
import com.pms.service.elevenst.ElevenstPaths;
import com.pms.service.elevenst.ElevenstTimes;
import com.pms.service.elevenst.ElevenstXml;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 11st customer inquiry load (FEATURE_2610_17 / D16 · D26 · D29 · D44 · D45 · D68 · D69).
 *
 * <ul>
 *   <li>Product Q&A 1626 → {@code PRODUCT_QNA}; status = {@code answerYn} (Y answered, else unanswered); the
 *       seller answer {@code answerCont} is the one reply.</li>
 *   <li>Urgent notice 1796, only {@code emerNtceClfNo1} 10 「긴급문의」 → {@code CALL_CENTER} (11 「긴급알림」 is a
 *       notice and is skipped, D26). Status = D44 table; previous answers = {@code emerReplyList > emerReplyCtnt}.</li>
 *   <li>Window: the last 7 days (today − 6 ~ today, both ends included) — Q&A once, urgent notices once per open
 *       status 01 · 02 · 04. The nightly round also reads the closed statuses 03 · 05 · 06 over the last 30 days
 *       (today − 29 ~ today), one call per status (D29 · D50 · D51).</li>
 *   <li>Non-delivery reports 6733 → {@code NON_DELIVERY}, every round: shipped date ({@code shDateType} 03) over the
 *       last 30 days (today − 29 ~ today), no query. Status = {@code sellerDtlsRsn} empty → unanswered, filled →
 *       answered with that handling as the one reply (D68 · D69).</li>
 * </ul>
 * Like the Coupang adapter, one type failing does not stop the other; the failure is rethrown at the end so the
 * caller does not stamp {@code lastInquirySyncAt}. STALE sweep first ({@link InquiryStaleSweeper}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenstInquiryAdapter implements InquirySyncAdapter {

    /** D29: 7 days with both ends included. */
    static final int WINDOW_DAYS_BACK = 6;
    /** D51: the nightly closed-status window — 30 days with both ends included (1796 limit = stale days). */
    static final int NIGHTLY_WINDOW_DAYS_BACK = 29;
    /** D51: statuses that still need an answer — every round. */
    static final List<String> OPEN_ALIMI_STATUSES = List.of("01", "02", "04");
    /** D51: finished statuses — nightly round only. */
    static final List<String> CLOSED_ALIMI_STATUSES = List.of("03", "05", "06");
    private static final String URGENT_INQUIRY = "10";
    /** D68: 6733 searched by shipped date. */
    static final String NON_DELIVERY_DATE_TYPE = "03";
    /** D69: the non-delivery window — 30 days with both ends included. */
    static final int NON_DELIVERY_WINDOW_DAYS_BACK = 29;
    private static final String NON_DELIVERY_SENTENCE = "고객이 상품을 받지 못했다고 신고했습니다.";

    private final ElevenstOrderApi orderApi;
    /** D68: 6733 answers with {@code resultCode}, which {@link ElevenstOrderApi#listByDay} does not check. */
    private final ElevenstApiClient client;
    private final InquiryUpserter inquiryUpserter;
    private final InquiryStaleSweeper inquiryStaleSweeper;

    @Override
    public Platform platform() {
        return Platform.ELEVENST;
    }

    @Override
    public InquirySyncResult syncInquiries(MarketplaceAccount account) {
        return syncInquiries(account, false);
    }

    @Override
    public InquirySyncResult syncInquiries(MarketplaceAccount account, boolean nightly) {
        int staleClosed = inquiryStaleSweeper.sweep(account);
        LocalDate to = LocalDate.now(SyncWindow.KST);
        LocalDate from = to.minusDays(WINDOW_DAYS_BACK);
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        int upserted = 0;
        int calls = 1;
        RuntimeException failure = null;
        try {
            for (Element row : orderApi.listByDay(account, ElevenstPaths.QNA_LIST, Map.of("answerStatus", "00"),
                    "brdInfoNo", from, to)) {
                inquiryUpserter.upsert(account, qna(ElevenstOrderApi.fields(row), now));
                upserted++;
            }
        } catch (RuntimeException e) {
            log.warn("[11st] Q&A sync failed: account={}", account.getId(), e);
            failure = e;
        }
        List<String> statuses = new ArrayList<>(OPEN_ALIMI_STATUSES);
        if (nightly) {
            statuses.addAll(CLOSED_ALIMI_STATUSES);
        }
        for (String status : statuses) {
            LocalDate statusFrom = OPEN_ALIMI_STATUSES.contains(status) ? from : to.minusDays(NIGHTLY_WINDOW_DAYS_BACK);
            calls++;
            try {
                for (Element row : orderApi.listByDay(account, ElevenstPaths.ALIMI_LIST,
                        Map.of("emerNtceCrntCd", status), "emerNtceSeq", statusFrom, to)) {
                    InquiryRecord record = urgent(row, now);
                    if (record != null) {
                        inquiryUpserter.upsert(account, record);
                        upserted++;
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[11st] urgent-notice sync failed: account={} status={}", account.getId(), status, e);
                failure = e;
            }
        }
        calls++;
        try {
            for (Element row : nonDeliveryRows(account, to.minusDays(NON_DELIVERY_WINDOW_DAYS_BACK), to)) {
                inquiryUpserter.upsert(account, nonDelivery(ElevenstOrderApi.fields(row), now));
                upserted++;
            }
        } catch (RuntimeException e) {
            log.warn("[11st] non-delivery sync failed: account={}", account.getId(), e);
            failure = e;
        }
        if (failure != null) {
            throw failure;
        }
        return new InquirySyncResult(calls, calls, upserted, staleClosed);
    }

    /** One 1626 row → record. {@code inquired_at} is NOT NULL — an unreadable {@code createDt} becomes {@code now}. */
    static InquiryRecord qna(Map<String, String> row, LocalDateTime now) {
        String id = text(row, "brdInfoNo");
        boolean answered = "Y".equalsIgnoreCase(text(row, "answerYn"));
        LocalDateTime answeredAt = ElevenstTimes.parse(text(row, "answerDt"));
        String answer = text(row, "answerCont");
        List<InquiryRecord.ReplyRecord> replies = (answered && answer != null)
                ? List.of(new InquiryRecord.ReplyRecord(id + "-answer", null, InquiryAuthorRole.SELLER, null,
                        answer, null, answeredAt))
                : List.of();
        return new InquiryRecord(InquiryType.PRODUCT_QNA, id, null, text(row, "ordNoDe"), text(row, "brdInfoClfNo"),
                text(row, "prdNm"), content(text(row, "brdInfoSbjct"), text(row, "brdInfoCont")), null,
                answered ? InquiryStatus.ANSWERED : InquiryStatus.UNANSWERED, answered ? "Y" : "N",
                orNow(ElevenstTimes.parse(text(row, "createDt")), now), answeredAt, replies);
    }

    /** One 1796 row → record; null for a notice ({@code emerNtceClfNo1} other than 10). */
    static InquiryRecord urgent(Element row, LocalDateTime now) {
        Map<String, String> fields = ElevenstOrderApi.fields(row);
        if (!URGENT_INQUIRY.equals(text(fields, "emerNtceClfNo1"))) {
            return null;
        }
        String id = text(fields, "emerNtceSeq");
        List<InquiryRecord.ReplyRecord> replies = new ArrayList<>();
        int n = 0;
        for (Element reply : ElevenstXml.elements(row, "emerReplyCtnt")) {
            String text = reply.getTextContent() == null ? "" : reply.getTextContent().trim();
            if (!text.isEmpty()) {
                replies.add(new InquiryRecord.ReplyRecord(id + "-reply-" + (++n), null, InquiryAuthorRole.SELLER,
                        null, text, null, null));
            }
        }
        String status = text(fields, "emerNtceCrntCd");
        LocalDateTime inquiredAt = ElevenstTimes.parse(join(text(fields, "createDt"), text(fields, "createTm")));
        if (inquiredAt == null) {
            inquiredAt = ElevenstTimes.parse(text(fields, "createDt"));
        }
        return new InquiryRecord(InquiryType.CALL_CENTER, id, text(fields, "ordPrdSeq"), text(fields, "ordNo"), null,
                text(fields, "prdNm"), content(text(fields, "emerNtceSubject"), text(fields, "emerCtnt")), null,
                urgentStatus(status), status == null ? "" : status, orNow(inquiredAt, now), null, replies);
    }

    /** 6733 rows; a negative {@code resultCode} is a failure like a negative {@code result_code} (D68). */
    private List<Element> nonDeliveryRows(MarketplaceAccount account, LocalDate from, LocalDate to) {
        byte[] raw = client.get(ElevenstPaths.fill(ElevenstPaths.NON_DELIVERY_LIST, Map.of(
                "shDateType", NON_DELIVERY_DATE_TYPE,
                "start", ElevenstTimes.DAY.format(from),
                "end", ElevenstTimes.DAY.format(to))), account);
        String code = ElevenstXml.firstText(ElevenstXml.parseOrNull(raw), "resultCode");
        if (code != null && code.startsWith("-")) {
            throw new BusinessException(ElevenstXml.messageOf(raw), HttpStatus.BAD_GATEWAY);
        }
        return ElevenstOrderApi.rowElements(raw, "nonDlvSeq");
    }

    /**
     * One 6733 row → record (D68). Content = our own sentence plus the filled parts of quantity · shipped time ·
     * carrier and invoice; name, address, phone and amount fields are not kept (D7 · D43).
     */
    static InquiryRecord nonDelivery(Map<String, String> row, LocalDateTime now) {
        String id = text(row, "nonDlvSeq");
        String handled = text(row, "sellerDtlsRsn");
        boolean answered = handled != null;
        LocalDateTime answeredAt = answered ? ElevenstTimes.parse(text(row, "updateDt")) : null;
        List<InquiryRecord.ReplyRecord> replies = answered
                ? List.of(new InquiryRecord.ReplyRecord(id + "-answer", null, InquiryAuthorRole.SELLER, null,
                        handledLine(handled, text(row, "resendDlvEtprsCdNm"), text(row, "resendInvcNo")), null,
                        answeredAt))
                : List.of();
        String product = text(row, "prdNm");
        String option = text(row, "optNm");
        String itemName = (product != null && option != null) ? product + " / " + option : product;
        String status = text(row, "nonDlvStatNm");
        return new InquiryRecord(InquiryType.NON_DELIVERY, id, text(row, "ordPrdSeq"), text(row, "ordNo"), null,
                itemName, nonDeliveryContent(row), null,
                answered ? InquiryStatus.ANSWERED : InquiryStatus.UNANSWERED, status == null ? "" : status,
                orNow(ElevenstTimes.parse(text(row, "nonDlvReqDt")), now), answeredAt, replies);
    }

    /** 「고객이 상품을 받지 못했다고 신고했습니다. 수량 {ordQty}개 · 발송 {sndEndDt} · {dlvEtprsNm} {invcNo}」, empty parts left out. */
    static String nonDeliveryContent(Map<String, String> row) {
        List<String> parts = new ArrayList<>();
        String qty = text(row, "ordQty");
        if (qty != null) {
            parts.add("수량 " + qty + "개");
        }
        String sent = text(row, "sndEndDt");
        if (sent != null) {
            parts.add("발송 " + sent);
        }
        String parcel = String.join(" ", Stream.of(text(row, "dlvEtprsNm"), text(row, "invcNo"))
                .filter(Objects::nonNull).toList());
        if (!parcel.isEmpty()) {
            parts.add(parcel);
        }
        return parts.isEmpty() ? NON_DELIVERY_SENTENCE : NON_DELIVERY_SENTENCE + " " + String.join(" · ", parts);
    }

    /** 「{sellerDtlsRsn}」 + for a re-shipping 「 (재발송 {carrier} {invoice})」 (D68). */
    static String handledLine(String handled, String carrier, String invoice) {
        if (carrier == null && invoice == null) {
            return handled;
        }
        String parcel = String.join(" ", Stream.of(carrier, invoice)
                .filter(Objects::nonNull).toList());
        return handled + " (재발송 " + parcel + ")";
    }

    /** D44 · D45: 01 · 02 · 04 → unanswered, 03 · 05 → answered, 06 → closed, anything else → answered. */
    static InquiryStatus urgentStatus(String code) {
        if (code == null) {
            return InquiryStatus.ANSWERED;
        }
        return switch (code) {
            case "01", "02", "04" -> InquiryStatus.UNANSWERED;
            case "06" -> InquiryStatus.CLOSED;
            default -> InquiryStatus.ANSWERED;
        };
    }

    /** Subject and body as one text, a blank line between them; either may be missing. */
    private static String content(String subject, String body) {
        if (subject == null) {
            return body;
        }
        return body == null ? subject : subject + "\n\n" + body;
    }

    private static LocalDateTime orNow(LocalDateTime value, LocalDateTime now) {
        return value != null ? value : now;
    }

    private static String join(String day, String time) {
        return (day == null || time == null) ? null : day + time;
    }

    private static String text(Map<String, String> row, String field) {
        String value = row.get(field);
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
