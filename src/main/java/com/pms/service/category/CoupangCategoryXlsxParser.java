package com.pms.service.category;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.util.IOUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Coupang bulk-category xlsx (one file per top-level category) into {@link ParsedLeaf} rows
 * (FEATURE_2608_06 / 53).
 *
 * <p><b>Format (measured):</b> the {@code data} sheet has header rows 1–3 (section / column names / required
 * flags) and leaf rows from row 5 onward. Only <b>col A</b> ({@code 카테고리}) and <b>col B</b>
 * ({@code 판매대행수수료}) are read; option/notice columns are skipped. Col A =
 * {@code [leafCode] 대분류>중분류>...>leafName} — only the leaf carries a code, intermediate segments are names
 * only. Rows repeat per option variant (~4 dupes per leaf) → deduped by code (first row wins).</p>
 *
 * <p>⚠️ {@code XSSFWorkbook} (DOM) is used — sufficient for a single top-level file (식품 ≈ 4866 rows). For the
 * whole catalog (tens of thousands of rows) switch to the XSSF SAX / streaming reader. Hidden sheets are ignored.</p>
 *
 * <p>The fee is converted percent → fraction ({@code 10.6} → {@code 0.106}) so it matches the fraction unit
 * {@code PriceCalculator} subtracts ({@code 1 − commission − margin}). Note: {@code PlatformCategory.commissionRate}
 * is DECIMAL(5,2), so the stored value rounds (0.106 → 0.11) — a known precision caveat flagged in 52 for
 * re-review; the parser keeps full precision, the column scale rounds on persist.</p>
 */
@Component
public class CoupangCategoryXlsxParser {

    /** col A = {@code [12345] 대>중>...>leaf}: capture the numeric code and the remaining path. */
    private static final Pattern CODE_PATH = Pattern.compile("^\\[(\\d+)]\\s*(.+)$");
    private static final int FIRST_DATA_ROW = 4; // 0-based row 4 = the 5th spreadsheet row
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final DataFormatter formatter = new DataFormatter();

    public List<ParsedLeaf> parse(InputStream xlsx) {
        // The Coupang bulk template's xl/styles.xml (166 styled columns) inflates below POI's default
        // zip-bomb ratio guard (0.01) → larger files throw "Zip bomb detected!" on open. These are trusted
        // admin uploads, so disable the ratio check. Global static, set once per parse (idempotent).
        ZipSecureFile.setMinInflateRatio(0d);
        // Some files carry a huge hidden sheet part (e.g. 뷰티's sheet2.xml ≈ 103MB uncompressed) that POI
        // still loads on open — over its default 100MB per-byte-array cap → RecordFormatException. Raise it.
        IOUtils.setByteArrayMaxOverride(500_000_000);
        Map<String, ParsedLeaf> byCode = new LinkedHashMap<>(); // dedup by code, first row wins, order preserved
        try (XSSFWorkbook workbook = new XSSFWorkbook(xlsx)) {
            Sheet sheet = resolveDataSheet(workbook);
            if (sheet == null) {
                return new ArrayList<>();
            }
            for (int r = FIRST_DATA_ROW; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String colA = cellString(row.getCell(0));
                if (colA.isBlank()) {
                    continue;
                }
                Matcher m = CODE_PATH.matcher(colA.trim());
                if (!m.matches()) {
                    continue; // bracket-less rows (section headers / stray text) are skipped
                }
                String code = m.group(1);
                if (byCode.containsKey(code)) {
                    continue; // first occurrence wins
                }
                List<String> segments = splitPath(m.group(2));
                if (segments.isEmpty()) {
                    continue;
                }
                byCode.put(code, new ParsedLeaf(code, feeFraction(row.getCell(1)), segments));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Coupang category xlsx", e);
        }
        return new ArrayList<>(byCode.values());
    }

    /** The named {@code data} sheet if present (case-insensitive), else the first visible sheet. */
    private Sheet resolveDataSheet(XSSFWorkbook workbook) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (!workbook.isSheetHidden(i) && !workbook.isSheetVeryHidden(i)
                    && "data".equalsIgnoreCase(workbook.getSheetName(i))) {
                return workbook.getSheetAt(i);
            }
        }
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (!workbook.isSheetHidden(i) && !workbook.isSheetVeryHidden(i)) {
                return workbook.getSheetAt(i);
            }
        }
        return null;
    }

    private List<String> splitPath(String path) {
        List<String> segments = new ArrayList<>();
        for (String seg : path.split(">")) {
            String trimmed = seg.trim();
            if (!trimmed.isEmpty()) {
                segments.add(trimmed);
            }
        }
        return segments;
    }

    private String cellString(Cell cell) {
        return cell == null ? "" : formatter.formatCellValue(cell);
    }

    /** col B (percent, e.g. {@code 10.6}) → fraction ({@code 0.106}); blank/non-numeric → null. */
    private BigDecimal feeFraction(Cell cell) {
        String raw = cellString(cell).trim().replace("%", "");
        if (raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw).divide(HUNDRED);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
