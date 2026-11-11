package com.pms.service.lotteon;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.MarketplaceAccount;
import com.pms.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LotteON order-side calls (FEATURE_2611_23 / D10 · D11 · D15 · D16 · D39): [209] outbound instructions · [140]
 * delivery progress states · [210] instruction receipt · [298] progress inform · [139] invoice modification.
 *
 * <p>Every call goes through {@link LotteonApiClient#post} with the channel's own key — a whole-call failure
 * ({@code returnCode} ≠ {@code 0000} · 4xx · 5xx · dev switch off) is thrown there (D10 · D15). This class reads the
 * per-call result ({@code data.rsltCd}) and per-line failures ({@code data.failList}).
 *
 * <p>⚠️ No write here is called by the sync — [210] · [298] · [139] run only from a person's button (D16 · D30).
 */
@Component
@RequiredArgsConstructor
public class LotteonOrderApi {

    /** D14: LotteON times are KST {@code yyyyMMddHHmmss}. */
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final String SUCCESS = "0000";

    private final LotteonApiClient lotteonApiClient;

    /** [209] outbound instructions of one window — delivery orders not yet received ({@code ifCplYN} blank, D11). */
    public List<JsonNode> deliveryOrders(MarketplaceAccount account, LocalDateTime from, LocalDateTime to) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("srchStrtDt", from.format(TIME));
        body.put("srchEndDt", to.format(TIME));
        body.put("odPrgsStepCd", "11");
        body.put("odTypCd", "10");
        body.put("ifCplYN", "");
        return list(post(account, LotteonPaths.DELIVERY_ORDERS, body), "deliveryOrderList");
    }

    /** [140] progress states of one window (D11 · D20). */
    public List<JsonNode> progressStates(MarketplaceAccount account, LocalDateTime from, LocalDateTime to) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("srchStrtDt", from.format(TIME));
        body.put("srchEndDt", to.format(TIME));
        return list(post(account, LotteonPaths.PROGRESS_STATES, body), "deliveryProgressStateList");
    }

    /** [140] progress states of one order, no period (D19 · D20). */
    public List<JsonNode> progressStatesOfOrder(MarketplaceAccount account, String odNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("odNo", odNo);
        return list(post(account, LotteonPaths.PROGRESS_STATES, body), "deliveryProgressStateList");
    }

    /**
     * [210] instruction receipt (D16). {@code data.rsltCd} ≠ {@code 0000} → 502 with LotteON's text (D15 · D44).
     */
    public void informIfComplete(MarketplaceAccount account, List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ifCompleteList", lines);
        requireSuccess(post(account, LotteonPaths.IF_COMPLETE, body));
    }

    /** [298] progress inform (D18). Returns the lines LotteON listed in {@code data.failList} (D15). */
    public List<LineFailure> informProgress(MarketplaceAccount account, List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deliveryProgressStateList", lines);
        JsonNode answer = post(account, LotteonPaths.PROGRESS_INFORM, body);
        List<LineFailure> failures = new ArrayList<>();
        for (JsonNode line : answer.path("data").path("failList")) {
            String message = LotteonOrderRows.text(line, "rsltMsg");
            failures.add(new LineFailure(LotteonOrderRows.text(line, "odNo"), LotteonOrderRows.text(line, "odSeq"),
                    LotteonOrderRows.text(line, "rsltCd"),
                    message != null ? message : LotteonApiClient.messageOf(answer, 200)));
        }
        return failures;
    }

    /** [139] invoice modification (D19) — the body is the line array itself. Same result rule as [210]. */
    public void modifyInvoice(MarketplaceAccount account, List<Map<String, Object>> lines) {
        requireSuccess(post(account, LotteonPaths.INVOICE_MODIFY, lines));
    }

    /** D14: the first 14 characters as {@link #TIME}; blank or unreadable → null. */
    public static LocalDateTime parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        if (value.length() < 14) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.substring(0, 14), TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** One line LotteON refused in {@code data.failList} (D15). */
    public record LineFailure(String odNo, String odSeq, String code, String message) {
    }

    private JsonNode post(MarketplaceAccount account, String path, Object body) {
        return lotteonApiClient.post(path, body, LotteonCredentials.of(account).apiKey());
    }

    private static void requireSuccess(JsonNode answer) {
        String code = LotteonOrderRows.text(answer.path("data"), "rsltCd");
        if (!SUCCESS.equals(code)) {
            String message = LotteonOrderRows.text(answer.path("data"), "rsltMsg");
            throw new BusinessException(message != null ? message : LotteonApiClient.messageOf(answer, 200),
                    HttpStatus.BAD_GATEWAY);
        }
    }

    private static List<JsonNode> list(JsonNode answer, String field) {
        List<JsonNode> rows = new ArrayList<>();
        answer.path("data").path(field).forEach(rows::add);
        return rows;
    }
}
