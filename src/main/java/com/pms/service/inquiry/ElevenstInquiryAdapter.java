package com.pms.service.inquiry;

import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Platform;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.elevenst.ElevenstOrderApi;
import com.pms.service.elevenst.ElevenstPaths;
import com.pms.service.elevenst.ElevenstTimes;
import com.pms.service.elevenst.ElevenstXml;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 11st customer inquiry load (FEATURE_2610_17 / D16 · D26 · D29 · D44 · D45).
 *
 * <ul>
 *   <li>Product Q&A 1626 → {@code PRODUCT_QNA}; status = {@code answerYn} (Y answered, else unanswered); the
 *       seller answer {@code answerCont} is the one reply.</li>
 *   <li>Urgent notice 1796, only {@code emerNtceClfNo1} 10 「긴급문의」 → {@code CALL_CENTER} (11 「긴급알림」 is a
 *       notice and is skipped, D26). Status = D44 table; previous answers = {@code emerReplyList > emerReplyCtnt}.</li>
 *   <li>Window: the last 7 days (today − 6 ~ today, both ends included) — Q&A once, urgent notices once per open
 *       status 01 · 02 · 04. The nightly round also reads the closed statuses 03 · 05 · 06 over the last 30 days
 *       (today − 29 ~ today), one call per status (D29 · D50 · D51).</li>
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

    private final ElevenstOrderApi orderApi;
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
