package com.pms.service.category;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the 11st seller-office fee notice page saved by a browser (FEATURE_2610_10 / D22 · D27 ①~⑤ · D33).
 *
 * <p>Rules, in order: ① charset = the {@code <meta … charset=…>} found in the first 4096 bytes, EUC-KR when
 * absent or unsupported ② the table = the first one whose header row holds both 「그룹군」 and 「대카테고리」
 * ③ rowspan cells are copied down; only the leading digits of a rowspan value count (the saved page has
 * {@code rowspan="5&gt;&lt;div class="}); rows whose own cells are all blank and header rows are skipped
 * ④ the 3rd column must be {@code N%} ⑤ the 4th column splits on 「,」 into groups, each ending in
 * {@code (N%)}; names inside a group split on 「;」; text with no {@code (N%)} means no exception.</p>
 */
@Component
public class ElevenstFeeTableParser {

    static final String DEFAULT_CHARSET = "EUC-KR";
    static final String NO_TABLE = "11번가 수수료 안내 표를 찾지 못했습니다.";

    private static final int CHARSET_SCAN_BYTES = 4096;
    private static final int COLUMNS = 4;
    private static final Pattern META_CHARSET =
            Pattern.compile("(?i)<meta[^>]*charset\\s*=\\s*[\"']?([A-Za-z0-9_-]+)");
    private static final Pattern FEE = Pattern.compile("^(\\d+)%$");
    private static final Pattern GROUP_TAIL = Pattern.compile("^(.*)\\((\\d+)%\\)$");
    private static final Pattern LEADING_DIGITS = Pattern.compile("^(\\d+)");

    /**
     * @param html the uploaded file bytes
     * @return the table rows in page order
     * @throws IllegalArgumentException (→ 400) when no fee table is found
     */
    public List<ElevenstFeeRow> parse(byte[] html) {
        Document doc;
        try {
            doc = Jsoup.parse(new ByteArrayInputStream(html), charsetOf(html), "");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the 11st fee notice file", e);
        }
        Element table = feeTable(doc);

        List<ElevenstFeeRow> rows = new ArrayList<>();
        int[] carryLeft = new int[COLUMNS];
        String[] carryText = new String[COLUMNS];
        for (Element tr : rowsOf(table)) {
            List<Element> cells = tr.children().stream()
                    .filter(c -> c.normalName().equals("td") || c.normalName().equals("th"))
                    .toList();
            if (cells.stream().anyMatch(c -> c.normalName().equals("th"))
                    || cells.stream().allMatch(c -> c.text().isBlank())) {
                continue;
            }
            String[] values = new String[COLUMNS];
            int next = 0;
            for (int col = 0; col < COLUMNS; col++) {
                if (carryLeft[col] > 0) {
                    values[col] = carryText[col];
                    carryLeft[col]--;
                } else if (next < cells.size()) {
                    Element cell = cells.get(next++);
                    values[col] = cell.text().trim();
                    int span = rowspan(cell);
                    if (span > 1) {
                        carryLeft[col] = span - 1;
                        carryText[col] = values[col];
                    }
                } else {
                    values[col] = "";
                }
            }
            rows.add(toRow(values));
        }
        return rows;
    }

    static String charsetOf(byte[] html) {
        String head = new String(html, 0, Math.min(html.length, CHARSET_SCAN_BYTES), StandardCharsets.ISO_8859_1);
        Matcher m = META_CHARSET.matcher(head);
        if (m.find() && Charset.isSupported(m.group(1))) {
            return m.group(1);
        }
        return DEFAULT_CHARSET;
    }

    private static Element feeTable(Document doc) {
        for (Element table : doc.select("table")) {
            for (Element tr : rowsOf(table)) {
                if (tr.children().stream().noneMatch(c -> c.normalName().equals("th"))) {
                    continue;
                }
                String header = strip(tr.text());
                if (header.contains("그룹군") && header.contains("대카테고리")) {
                    return table;
                }
                break;
            }
        }
        throw new IllegalArgumentException(NO_TABLE);
    }

    /** Rows of this table only (not of a table nested inside it). */
    private static List<Element> rowsOf(Element table) {
        return table.select("tr").stream().filter(tr -> tr.closest("table") == table).toList();
    }

    private static int rowspan(Element cell) {
        Matcher m = LEADING_DIGITS.matcher(cell.attr("rowspan").trim());
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    private static ElevenstFeeRow toRow(String[] values) {
        Matcher fee = FEE.matcher(strip(values[2]));
        Integer percent = fee.matches() ? Integer.valueOf(fee.group(1)) : null;
        return new ElevenstFeeRow(values[0], values[1], values[2], percent, exclusions(values[3]));
    }

    private static List<ElevenstFeeRow.Excluded> exclusions(String text) {
        List<ElevenstFeeRow.Excluded> result = new ArrayList<>();
        for (String group : text.split(",")) {
            Matcher m = GROUP_TAIL.matcher(group.trim());
            if (!m.matches()) {
                continue;
            }
            int percent = Integer.parseInt(m.group(2));
            for (String name : m.group(1).split(";")) {
                if (!name.isBlank()) {
                    result.add(new ElevenstFeeRow.Excluded(name.trim(), percent));
                }
            }
        }
        return result;
    }

    /** Removes every whitespace character, including no-break spaces. */
    static String strip(String s) {
        return s.replaceAll("[\\s\\u00A0]+", "");
    }
}
