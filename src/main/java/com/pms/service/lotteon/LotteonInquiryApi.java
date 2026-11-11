package com.pms.service.lotteon;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.MarketplaceAccount;
import com.pms.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LotteON customer inquiry calls (FEATURE_2611_23 / D31 · D66 · D81 · D83 · R40 · R41): [99] product Q&A list · [100]
 * its answer · [179] seller inquiry list · [181] its answer · [144] seller contact list · [146] its answer · [68]
 * non-receipt report list · [66] its withdrawal request.
 *
 * <p>Result rules (R40):</p>
 * <ul>
 *   <li>[99] · [100] · [66] — {@link LotteonApiClient#post}'s {@code returnCode} check; [100] also reads the per-line
 *       result ({@link LotteonApiClient#lineFailure}).</li>
 *   <li>[179] · [144] · [181] · [146] — the top-level {@code rsltCd}: {@code 0000} success, {@code 3000} on a list =
 *       empty, anything else = 502 with LotteON's text ({@code rsltMsg}, then {@code rslt}, then
 *       {@link LotteonApiClient#messageOf}) (D81).</li>
 *   <li>[68] — {@link LotteonApiClient#postList} ({@code returnCode} 3000 = empty, D66).</li>
 * </ul>
 * Pages (R41): [99] by {@value #QNA_PAGE_SIZE}, [179] · [144] by {@value #CS_PAGE_SIZE}, from page 1 until a page holds
 * fewer rows than its size; [68] has no pages. Fields whose value is null are not sent.
 *
 * <p>❌ No contact or address field is read here (D9) — callers keep only the fields of the inquiry record.
 */
@Component
@RequiredArgsConstructor
public class LotteonInquiryApi {

    /** [99] rows per page (MAX 100, R41). */
    static final int QNA_PAGE_SIZE = 100;
    /** [179] · [144] rows per page (R41). */
    static final int CS_PAGE_SIZE = 50;
    /** [179] · [144] search days. */
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String RESULT_SUCCESS = "0000";
    /** D83: every Q&A status, so answers given in the LotteON seller center move the status too. */
    private static final String QNA_ALL_STATUSES = "ALL";

    private final LotteonApiClient lotteonApiClient;

    /** [99] product Q&A registered in the window, every status (D83). Rows = {@code data[]}. */
    public List<JsonNode> qnaList(MarketplaceAccount account, LocalDateTime from, LocalDateTime to) {
        LotteonKey key = LotteonCredentials.of(account);
        List<JsonNode> rows = new ArrayList<>();
        for (int page = 1; ; page++) {
            Map<String, Object> body = new LinkedHashMap<>();
            putIfPresent(body, "trGrpCd", key.trGrpCd());
            putIfPresent(body, "trNo", key.trNo());
            body.put("regStrDttm", from.format(LotteonOrderApi.TIME));
            body.put("regEndDttm", to.format(LotteonOrderApi.TIME));
            body.put("qnaStatCd", QNA_ALL_STATUSES);
            body.put("pageNo", page);
            body.put("rowsPerPage", QNA_PAGE_SIZE);
            List<JsonNode> pageRows = new ArrayList<>();
            lotteonApiClient.post(LotteonPaths.QNA_LIST, body, key.apiKey()).path("data").forEach(pageRows::add);
            rows.addAll(pageRows);
            if (pageRows.size() < QNA_PAGE_SIZE) {
                return rows;
            }
        }
    }

    /** [179] seller inquiries received in the days, every status. Rows = {@code rsltList[]}. */
    public List<JsonNode> sellerInquiries(MarketplaceAccount account, LocalDate from, LocalDate to) {
        return csPages(account, LotteonPaths.SELLER_INQUIRY_LIST, from, to);
    }

    /** [144] seller contact rows sent in the days. Rows = {@code rsltList[]} — one per contact, not per group. */
    public List<JsonNode> sellerContacts(MarketplaceAccount account, LocalDate from, LocalDate to) {
        return csPages(account, LotteonPaths.SELLER_CONTACT_LIST, from, to);
    }

    /** [68] non-receipt reports of the window (D66 — {@code returnCode} 3000 = empty). Rows = {@code data[]}. */
    public List<JsonNode> nonReceipts(MarketplaceAccount account, LocalDateTime from, LocalDateTime to) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("srchStrtDttm", from.format(LotteonOrderApi.TIME));
        body.put("srchEndDttm", to.format(LotteonOrderApi.TIME));
        List<JsonNode> rows = new ArrayList<>();
        lotteonApiClient.postList(LotteonPaths.NON_RECEIPT_LIST, body, LotteonCredentials.of(account).apiKey())
                .path("data").forEach(rows::add);
        return rows;
    }

    /** [100] product Q&A answer — {@code returnCode}, then the per-line result (R40). */
    public void replyQna(MarketplaceAccount account, Map<String, Object> body) {
        JsonNode answer = lotteonApiClient.post(LotteonPaths.QNA_REPLY, body, LotteonCredentials.of(account).apiKey());
        String failure = LotteonApiClient.lineFailure(answer);
        if (failure != null) {
            throw new BusinessException(failure, HttpStatus.BAD_GATEWAY);
        }
    }

    /** [181] seller inquiry answer — {@code rsltCd} (D81). */
    public void replySellerInquiry(MarketplaceAccount account, Map<String, Object> body) {
        requireResult(lotteonApiClient.post(LotteonPaths.SELLER_INQUIRY_REPLY, body,
                LotteonCredentials.of(account).apiKey()));
    }

    /** [146] seller contact answer — {@code rsltCd}, text in {@code rslt} (D81 · R40). */
    public void replySellerContact(MarketplaceAccount account, Map<String, Object> body) {
        requireResult(lotteonApiClient.post(LotteonPaths.SELLER_CONTACT_REPLY, body,
                LotteonCredentials.of(account).apiKey()));
    }

    /** [66] non-receipt report withdrawal request — the {@code returnCode} check only. */
    public void withdrawNonReceipt(MarketplaceAccount account, Map<String, Object> body) {
        lotteonApiClient.post(LotteonPaths.NON_RECEIPT_WITHDRAW, body, LotteonCredentials.of(account).apiKey());
    }

    /** [179] · [144] page by page (R41). */
    private List<JsonNode> csPages(MarketplaceAccount account, String path, LocalDate from, LocalDate to) {
        String apiKey = LotteonCredentials.of(account).apiKey();
        List<JsonNode> rows = new ArrayList<>();
        for (int page = 1; ; page++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("scStrtDt", from.format(DAY));
            body.put("scEndDt", to.format(DAY));
            body.put("pageNo", page);
            body.put("rowsPerPage", CS_PAGE_SIZE);
            List<JsonNode> pageRows = rsltRows(lotteonApiClient.post(path, body, apiKey));
            rows.addAll(pageRows);
            if (pageRows.size() < CS_PAGE_SIZE) {
                return rows;
            }
        }
    }

    /** D81 · D66: {@code rsltCd} 0000 → {@code rsltList[]}, 3000 → empty, anything else → 502 with LotteON's text. */
    private static List<JsonNode> rsltRows(JsonNode answer) {
        if (LotteonApiClient.NO_DATA.equals(LotteonOrderRows.text(answer, "rsltCd"))) {
            return List.of();
        }
        requireResult(answer);
        List<JsonNode> rows = new ArrayList<>();
        answer.path("rsltList").forEach(rows::add);
        return rows;
    }

    /** D81: {@code rsltCd} ≠ {@code 0000} → 502 with {@code rsltMsg}, else {@code rslt}, else the body's message. */
    private static void requireResult(JsonNode answer) {
        if (RESULT_SUCCESS.equals(LotteonOrderRows.text(answer, "rsltCd"))) {
            return;
        }
        String message = LotteonOrderRows.text(answer, "rsltMsg");
        if (message == null) {
            message = LotteonOrderRows.text(answer, "rslt");
        }
        throw new BusinessException(message != null ? message : LotteonApiClient.messageOf(answer, 200),
                HttpStatus.BAD_GATEWAY);
    }

    private static void putIfPresent(Map<String, Object> body, String field, Object value) {
        if (value != null) {
            body.put(field, value);
        }
    }
}
