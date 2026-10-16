package com.pms.service.elevenst;

import com.pms.domain.MarketplaceAccount;
import com.pms.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * List calls and result checks of the 11st order · claim · inquiry APIs (FEATURE_2610_17). Every call goes through
 * {@link ElevenstApiClient} (D8 — dev stop, key-use stamp, 502 wording).
 *
 * <p><b>Rows</b>: a list row = the parent element of each {@code rowKey} element (e.g. {@code ordPrdSeq}). The guide
 * does not name every row element, and the root names differ per API ({@code ns2:orders} · {@code s2:orders}), so
 * rows are found by a field every row of that API carries. A row is read as its direct child elements
 * (local name → trimmed text).
 *
 * <p><b>Errors</b>: a list carries {@code result_code} · {@code result_text} under its root; a negative code is an
 * error (→ 502 with 11st's text), 0 = 「조회된 결과가 없습니다」 (not an error). A negative code whose text says there
 * are no rows (claim lists) is also an empty list — see {@link #isNoData(String)}.
 *
 * <p><b>D30</b>: a minute-window list that comes back with {@value #MAX_ROWS} rows is split in half and called again;
 * a half shorter than one hour is not split further (warn log).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenstOrderApi {

    /** 11st list cap (order · claim lists). */
    static final int MAX_ROWS = 3000;
    /** D30: no split below this window length. */
    static final Duration MIN_SPLIT = Duration.ofHours(1);

    private final ElevenstApiClient client;

    /**
     * Minute-precision list ({@code {start}} · {@code {end}} = {@code yyyyMMddHHmm}) with the D30 split.
     *
     * @return rows in response order (split halves are concatenated, earlier half first)
     */
    public List<Map<String, String>> listByMinute(MarketplaceAccount account, String pathTemplate, String rowKey,
                                                  LocalDateTime from, LocalDateTime to) {
        String path = ElevenstPaths.fill(pathTemplate, Map.of(
                "start", ElevenstTimes.MINUTE.format(from),
                "end", ElevenstTimes.MINUTE.format(to)));
        List<Map<String, String>> rows = fields(rowElements(client.get(path, account), rowKey));
        if (rows.size() < MAX_ROWS) {
            return rows;
        }
        Duration length = Duration.between(from, to);
        if (length.dividedBy(2).compareTo(MIN_SPLIT) < 0) {
            log.warn("[11st] list still full below the split floor — rows may be missing: path={} rows={}",
                    path, rows.size());
            return rows;
        }
        LocalDateTime middle = from.plus(length.dividedBy(2));
        List<Map<String, String>> all = new ArrayList<>(
                listByMinute(account, pathTemplate, rowKey, from, middle));
        all.addAll(listByMinute(account, pathTemplate, rowKey, middle, to));
        return all;
    }

    /** Day-precision list ({@code {start}} · {@code {end}} = {@code yyyyMMdd}) — Q&A · urgent notices. */
    public List<Element> listByDay(MarketplaceAccount account, String pathTemplate, Map<String, String> extra,
                                   String rowKey, LocalDate from, LocalDate to) {
        Map<String, String> values = new LinkedHashMap<>(extra);
        values.put("start", ElevenstTimes.DAY.format(from));
        values.put("end", ElevenstTimes.DAY.format(to));
        return rowElements(client.get(ElevenstPaths.fill(pathTemplate, values), account), rowKey);
    }

    /**
     * Row elements of a list response.
     *
     * @throws BusinessException 502 when the body is not XML or carries a negative {@code result_code}
     */
    public static List<Element> rowElements(byte[] raw, String rowKey) {
        Document doc = ElevenstXml.parseOrNull(raw);
        if (doc == null) {
            throw new BusinessException("11번가 응답을 읽을 수 없습니다", HttpStatus.BAD_GATEWAY);
        }
        String code = ElevenstXml.firstText(doc, "result_code");
        if (code != null && code.startsWith("-")) {
            if (isNoData(ElevenstXml.firstText(doc, "result_text"))) {
                return List.of();
            }
            throw new BusinessException(failureText(doc, raw), HttpStatus.BAD_GATEWAY);
        }
        Set<Element> rows = new LinkedHashSet<>();
        for (Element key : ElevenstXml.elements(doc, rowKey)) {
            if (key.getParentNode() instanceof Element parent) {
                rows.add(parent);
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Claim lists (cancel · return · exchange) answer an empty window with a negative code and a "no rows" text
     * (observed in prod: 「해당건이 없습니다」) instead of the order lists' 0 — that is an empty list, not an error.
     */
    static boolean isNoData(String text) {
        if (text == null) {
            return false;
        }
        String compact = text.replace(" ", "");
        return compact.contains("해당건이없습니다") || compact.contains("조회된결과가없습니다");
    }

    /** Direct child elements of each row as local name → trimmed text (the first one wins on a repeated name). */
    public static List<Map<String, String>> fields(List<Element> rows) {
        List<Map<String, String>> out = new ArrayList<>(rows.size());
        for (Element row : rows) {
            out.add(fields(row));
        }
        return out;
    }

    /** Direct child elements of one row as local name → trimmed text. */
    public static Map<String, String> fields(Element row) {
        Map<String, String> values = new LinkedHashMap<>();
        NodeList children = row.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                String name = element.getLocalName() != null ? element.getLocalName() : element.getTagName();
                String text = element.getTextContent();
                values.putIfAbsent(name, text == null ? "" : text.trim());
            }
        }
        return values;
    }

    /**
     * Result check of an action call ({@code ResultOrder} family): passes when {@code result_code} is one of
     * {@code successCodes}; otherwise 502 with 11st's {@code result_text} ({@link ElevenstRejectedException} keeps the
     * raw code and text).
     *
     * @return the {@code result_code} that passed
     */
    public static String requireResult(byte[] raw, Set<String> successCodes) {
        Document doc = ElevenstXml.parseOrNull(raw);
        String code = ElevenstXml.firstText(doc, "result_code");
        if (code != null && successCodes.contains(code)) {
            return code;
        }
        throw new ElevenstRejectedException(failureText(doc, raw), code, ElevenstXml.firstText(doc, "result_text"));
    }

    /** 11st's own failure wording: {@code result_text}, else the {@code message} / body text (D60 of 2610_13). */
    static String failureText(Document doc, byte[] raw) {
        String text = ElevenstXml.firstText(doc, "result_text");
        if (text != null && !text.isBlank()) {
            String code = ElevenstXml.firstText(doc, "result_code");
            return code == null ? text : text + " (" + code + ")";
        }
        return ElevenstXml.messageOf(raw);
    }
}
