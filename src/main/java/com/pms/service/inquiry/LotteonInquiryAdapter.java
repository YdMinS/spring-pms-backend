package com.pms.service.inquiry;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.CustomerInquiry;
import com.pms.domain.InquiryAuthorRole;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.domain.LotteonOrderLine;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderLine;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.repository.CustomerInquiryRepository;
import com.pms.repository.LotteonOrderLineRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.service.coupang.SyncWindow;
import com.pms.service.lotteon.LotteonInquiryApi;
import com.pms.service.lotteon.LotteonOrderApi;
import com.pms.service.lotteon.LotteonOrderRows;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LotteON customer inquiry load (FEATURE_2611_23 / D31 · D62 · D81 ~ D84 · R41 ~ R43 · R47).
 *
 * <ul>
 *   <li>Product Q&A 99 → {@code PRODUCT_QNA}, every status (D83): NPROC → unanswered, PROC → answered, CC_TCTL (handed
 *       to the call center) → closed (D84), anything else → answered. No answer text comes back.</li>
 *   <li>Seller inquiry 179 → {@code SELLER_INQUIRY}: ANS → answered with {@code ansCnts} as the one reply.</li>
 *   <li>Seller contact 144 → {@code CALL_CENTER}, one inquiry per contact group (D82): the head row
 *       ({@code slrCtctNo} = {@code slrCtctGrpNo}) is the inquiry, the other rows are its replies; the latest row
 *       decides the status.</li>
 *   <li>Non-receipt report 68 → {@code NON_DELIVERY}: a withdrawal request text means answered (R42 · R47).</li>
 *   <li>Window (R41): 99 · 179 · 144 = the last 7 days (today − 6 ~ today); 68 = the last 14 days.</li>
 * </ul>
 * Like the 11st adapter, one type failing does not stop the others; the last failure is rethrown at the end so the
 * caller does not stamp {@code lastInquirySyncAt}. STALE sweep first ({@link InquiryStaleSweeper}).
 *
 * <p>❌ No contact or address field is read (D9).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LotteonInquiryAdapter implements InquirySyncAdapter {

    /** R41: 7 days with both ends included. */
    static final int WINDOW_DAYS_BACK = 6;
    /** R41: the non-receipt window — the last 14 days up to now. */
    static final int NON_RECEIPT_DAYS = 14;
    /** D62: the stored seller contact title length. */
    private static final int TITLE_MAX_LENGTH = 100;

    private static final String SEND = "SND";
    private static final String RECEIVE = "RCV";
    private static final String SELLER_INQUIRY_ANSWERED = "ANS";

    private final LotteonInquiryApi lotteonInquiryApi;
    private final InquiryUpserter inquiryUpserter;
    private final InquiryStaleSweeper inquiryStaleSweeper;
    /** R43: LotteON inquiries link by order number (and item) through the order mirror. */
    private final LotteonOrderLineRepository lotteonOrderLineRepository;
    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    /** D82: a contact group whose head row is out of the window keeps its stored head. */
    private final CustomerInquiryRepository customerInquiryRepository;

    @Override
    public Platform platform() {
        return Platform.LOTTEON;
    }

    /**
     * R43: the option with this LotteON item number (not for a non-receipt report) and its listing; else the single
     * LotteON listing of this product number; else no link.
     */
    @Override
    public ListingMatch matchListing(MarketplaceAccount account, InquiryRecord record) {
        if (record.type() != InquiryType.NON_DELIVERY && record.externalItemId() != null) {
            List<ProductListingOption> options =
                    productListingOptionRepository.findLotteonOptionsByMarketId(account.getId(), record.externalItemId());
            if (!options.isEmpty()) {
                ProductListingOption option = options.get(0);
                return new ListingMatch(option.getProductListing(), option);
            }
        }
        if (record.externalProductId() != null) {
            List<ProductListing> listings =
                    productListingRepository.findLotteonListings(account.getId(), record.externalProductId());
            if (listings.size() == 1) {
                return new ListingMatch(listings.get(0), null);
            }
        }
        return new ListingMatch(null, null);
    }

    /**
     * R43: a non-receipt report = the exact mirror line ({@code odNo}, {@code odSeq}); otherwise the order's line with
     * the same {@code sitmNo} when exactly one, else the order's only line — ambiguous → no link.
     */
    @Override
    public OrderLine matchOrderLine(MarketplaceAccount account, InquiryRecord record) {
        if (record.externalOrderId() == null) {
            return null;
        }
        if (record.type() == InquiryType.NON_DELIVERY) {
            if (record.externalItemId() == null) {
                return null;
            }
            return lotteonOrderLineRepository
                    .findByMarketplaceAccount_IdAndOdNoAndOdSeq(account.getId(), record.externalOrderId(),
                            record.externalItemId())
                    .map(LotteonOrderLine::getOrderLine)
                    .orElse(null);
        }
        List<LotteonOrderLine> lines =
                lotteonOrderLineRepository.findByMarketplaceAccount_IdAndOdNo(account.getId(), record.externalOrderId());
        if (record.externalItemId() != null) {
            List<LotteonOrderLine> sameItem = lines.stream()
                    .filter(line -> record.externalItemId().equals(line.getSitmNo()))
                    .toList();
            if (sameItem.size() == 1) {
                return sameItem.get(0).getOrderLine();
            }
        }
        return lines.size() == 1 ? lines.get(0).getOrderLine() : null;
    }

    @Override
    public InquirySyncResult syncInquiries(MarketplaceAccount account) {
        int staleClosed = inquiryStaleSweeper.sweep(account);
        LocalDate today = LocalDate.now(SyncWindow.KST);
        LocalDate fromDay = today.minusDays(WINDOW_DAYS_BACK);
        LocalDateTime now = LocalDateTime.now(SyncWindow.KST);
        int upserted = 0;
        RuntimeException failure = null;
        try {
            for (JsonNode row : lotteonInquiryApi.qnaList(account, fromDay.atStartOfDay(), now)) {
                inquiryUpserter.upsert(account, qna(row, now), this);
                upserted++;
            }
        } catch (RuntimeException e) {
            log.warn("[LotteON] Q&A sync failed: account={}", account.getId(), e);
            failure = e;
        }
        try {
            for (JsonNode row : lotteonInquiryApi.sellerInquiries(account, fromDay, today)) {
                inquiryUpserter.upsert(account, sellerInquiry(row, now), this);
                upserted++;
            }
        } catch (RuntimeException e) {
            log.warn("[LotteON] seller inquiry sync failed: account={}", account.getId(), e);
            failure = e;
        }
        try {
            List<JsonNode> rows = lotteonInquiryApi.sellerContacts(account, fromDay, today);
            for (InquiryRecord record : sellerContacts(account, rows, now)) {
                inquiryUpserter.upsert(account, record, this);
                upserted++;
            }
        } catch (RuntimeException e) {
            log.warn("[LotteON] seller contact sync failed: account={}", account.getId(), e);
            failure = e;
        }
        try {
            for (JsonNode row : lotteonInquiryApi.nonReceipts(account, now.minusDays(NON_RECEIPT_DAYS), now)) {
                inquiryUpserter.upsert(account, nonReceipt(row, now), this);
                upserted++;
            }
        } catch (RuntimeException e) {
            log.warn("[LotteON] non-receipt sync failed: account={}", account.getId(), e);
            failure = e;
        }
        if (failure != null) {
            throw failure;
        }
        return new InquirySyncResult(4, 4, upserted, staleClosed);
    }

    /** One 99 row → record (D83 · D84). {@code inquired_at} is NOT NULL — an unreadable time becomes {@code now}. */
    static InquiryRecord qna(JsonNode row, LocalDateTime now) {
        String code = text(row, "qnaStatCd");
        return new InquiryRecord(InquiryType.PRODUCT_QNA, text(row, "pdQnaNo"), text(row, "sitmNo"), null,
                text(row, "spdNo"), null, content(text(row, "qstTtl"), text(row, "qstCnts")), null,
                qnaStatus(code), code == null ? "" : code,
                orNow(LotteonOrderApi.parse(text(row, "regDttm")), now), null, List.of());
    }

    /** One 179 row → record; an answered row carries {@code ansCnts} as the one seller reply (R42). */
    static InquiryRecord sellerInquiry(JsonNode row, LocalDateTime now) {
        String id = text(row, "slrInqNo");
        String code = text(row, "slrInqProcStatCd");
        boolean answered = SELLER_INQUIRY_ANSWERED.equals(code);
        LocalDateTime answeredAt = answered ? LotteonOrderApi.parse(text(row, "procDttm")) : null;
        String answer = text(row, "ansCnts");
        List<InquiryRecord.ReplyRecord> replies = (answered && answer != null)
                ? List.of(new InquiryRecord.ReplyRecord(id + "-answer", null, InquiryAuthorRole.SELLER, null, answer,
                        null, answeredAt))
                : List.of();
        return new InquiryRecord(InquiryType.SELLER_INQUIRY, id, text(row, "sitmNo"), text(row, "odNo"),
                text(row, "spdNo"), itemName(row), content(text(row, "inqTtl"), text(row, "inqCnts")), null,
                answered ? InquiryStatus.ANSWERED : InquiryStatus.UNANSWERED, code == null ? "" : code,
                orNow(LotteonOrderApi.parse(text(row, "accpDttm")), now), answeredAt, replies);
    }

    /** One 68 row → record (R42 · R47): a withdrawal request text = answered, that text the one seller reply. */
    static InquiryRecord nonReceipt(JsonNode row, LocalDateTime now) {
        String id = text(row, "nrcptDeclAccpNo");
        String handled = text(row, "nrcptDeclWhdrReqCnts");
        boolean answered = handled != null;
        List<InquiryRecord.ReplyRecord> replies = answered
                ? List.of(new InquiryRecord.ReplyRecord(id + "-answer", null, InquiryAuthorRole.SELLER, null, handled,
                        null, null))
                : List.of();
        return new InquiryRecord(InquiryType.NON_DELIVERY, id, text(row, "odSeq"), text(row, "odNo"),
                text(row, "spdNo"), itemName(row),
                nonReceiptContent(text(row, "clmRsnCd"), text(row, "nrcptDeclCnts")), null,
                answered ? InquiryStatus.ANSWERED : InquiryStatus.UNANSWERED, answered ? "Y" : "N",
                orNow(LotteonOrderApi.parse(text(row, "nrcptDeclAccpDttm")), now), null, replies);
    }

    /**
     * 144 rows → one record per contact group, in the order the groups first appear (D82). Head = the row whose
     * {@code slrCtctNo} is the group number; when it is not in the answer, a stored inquiry keeps its stored head
     * (content · title · time) and every row is a reply, else the earliest row is the head.
     */
    List<InquiryRecord> sellerContacts(MarketplaceAccount account, List<JsonNode> rows, LocalDateTime now) {
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String group = text(row, "slrCtctGrpNo");
            if (group != null) {
                groups.computeIfAbsent(group, key -> new ArrayList<>()).add(row);
            }
        }
        List<InquiryRecord> records = new ArrayList<>();
        for (Map.Entry<String, List<JsonNode>> entry : groups.entrySet()) {
            records.add(sellerContact(account, entry.getKey(), entry.getValue(), now));
        }
        return records;
    }

    private InquiryRecord sellerContact(MarketplaceAccount account, String group, List<JsonNode> rows,
                                        LocalDateTime now) {
        JsonNode head = rows.stream().filter(row -> group.equals(text(row, "slrCtctNo"))).findFirst().orElse(null);
        JsonNode first = earliest(rows);
        String content;
        String title;
        LocalDateTime inquiredAt;
        JsonNode source;
        List<JsonNode> replyRows;
        if (head != null) {
            source = head;
            content = text(head, "ctctCnts");
            title = title(text(head, "ctctTtl"));
            inquiredAt = LotteonOrderApi.parse(text(head, "sndDttm"));
            replyRows = rows.stream().filter(row -> row != head).toList();
        } else {
            Optional<CustomerInquiry> stored = customerInquiryRepository
                    .findByMarketplaceAccount_IdAndInquiryTypeAndExternalInquiryId(account.getId(),
                            InquiryType.CALL_CENTER, group);
            source = first;
            if (stored.isPresent()) {
                content = stored.get().getContent();
                title = stored.get().getTitle();
                inquiredAt = stored.get().getInquiredAt();
                replyRows = rows;
            } else {
                content = text(first, "ctctCnts");
                title = title(text(first, "ctctTtl"));
                inquiredAt = LotteonOrderApi.parse(text(first, "sndDttm"));
                replyRows = rows.stream().filter(row -> row != first).toList();
            }
        }

        JsonNode latest = latest(rows);
        String latestSide = text(latest, "sndDvsCd");
        boolean unanswered = SEND.equals(latestSide) && text(latest, "ansDttm") == null;
        LocalDateTime answeredAt = null;
        if (!unanswered) {
            answeredAt = RECEIVE.equals(latestSide)
                    ? LotteonOrderApi.parse(text(latest, "sndDttm"))
                    : LotteonOrderApi.parse(text(latest, "ansDttm"));
        }
        List<InquiryRecord.ReplyRecord> replies = replyRows.stream()
                .map(row -> new InquiryRecord.ReplyRecord(text(row, "slrCtctNo"), null,
                        RECEIVE.equals(text(row, "sndDvsCd")) ? InquiryAuthorRole.SELLER : InquiryAuthorRole.CS_AGENT,
                        null, text(row, "ctctCnts"), null, LotteonOrderApi.parse(text(row, "sndDttm"))))
                .toList();
        return new InquiryRecord(InquiryType.CALL_CENTER, group, null, text(source, "odNo"), text(source, "spdNo"),
                text(source, "spdNm"), content, null,
                unanswered ? InquiryStatus.UNANSWERED : InquiryStatus.ANSWERED, latestSide == null ? "" : latestSide,
                orNow(inquiredAt, now), answeredAt, replies, title);
    }

    /** The row with the earliest {@code sndDttm}; a tie keeps the earlier row of the answer. */
    private static JsonNode earliest(List<JsonNode> rows) {
        JsonNode found = rows.get(0);
        for (JsonNode row : rows) {
            if (sentAt(row).isBefore(sentAt(found))) {
                found = row;
            }
        }
        return found;
    }

    /** The row with the latest {@code sndDttm}; a tie takes the later row of the answer (D82). */
    private static JsonNode latest(List<JsonNode> rows) {
        JsonNode found = rows.get(0);
        for (JsonNode row : rows) {
            if (!sentAt(row).isBefore(sentAt(found))) {
                found = row;
            }
        }
        return found;
    }

    private static LocalDateTime sentAt(JsonNode row) {
        LocalDateTime value = LotteonOrderApi.parse(text(row, "sndDttm"));
        return value != null ? value : LocalDateTime.MIN;
    }

    /** D83 · D84: NPROC → unanswered, PROC → answered, CC_TCTL → closed, anything else → answered. */
    static InquiryStatus qnaStatus(String code) {
        if (code == null) {
            return InquiryStatus.ANSWERED;
        }
        return switch (code) {
            case "NPROC" -> InquiryStatus.UNANSWERED;
            case "CC_TCTL" -> InquiryStatus.CLOSED;
            default -> InquiryStatus.ANSWERED;
        };
    }

    /** R47: 「{reason name} — {report text}」; an empty text leaves the name only. */
    static String nonReceiptContent(String reasonCode, String text) {
        String name = nonReceiptReason(reasonCode);
        return text == null ? name : name + " — " + text;
    }

    private static String nonReceiptReason(String code) {
        if (code == null) {
            return "사유 코드";
        }
        return switch (code) {
            case "401" -> "전체 상품이 오지 않음";
            case "402" -> "일부 수량이 오지 않음";
            case "403" -> "상품의 구성품/부속품이 오지 않음";
            case "404" -> "배송중에서 흐름 멈춤";
            default -> "사유 코드 " + code;
        };
    }

    /** R7: {@code spdNm}, plus {@code / sitmNm} when an item name is present. */
    private static String itemName(JsonNode row) {
        return LotteonOrderRows.itemName(row);
    }

    /** D62: the first 100 characters of {@code ctctTtl}. */
    private static String title(String value) {
        return (value == null || value.length() <= TITLE_MAX_LENGTH) ? value : value.substring(0, TITLE_MAX_LENGTH);
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

    private static String text(JsonNode row, String field) {
        return LotteonOrderRows.text(row, field);
    }
}
