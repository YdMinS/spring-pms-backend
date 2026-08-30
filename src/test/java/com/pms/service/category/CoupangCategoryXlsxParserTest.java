package com.pms.service.category;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CoupangCategoryXlsxParser} (FEATURE_2608_06 / 53): xlsx fixtures are assembled in-memory with POI
 * (never committed binaries) — a {@code data} sheet with 3 dummy header rows and leaf rows from row 5. Verifies
 * dedup by code, path split, code/fee (percent → fraction) parsing, and skipping bracket-less rows.
 */
class CoupangCategoryXlsxParserTest {

    private final CoupangCategoryXlsxParser parser = new CoupangCategoryXlsxParser();

    /** Build a data-sheet xlsx: rows[i] = {colA, colB}; header rows 1-3 are filled with dummies. */
    private InputStream xlsx(String[]... dataRows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("data");
            for (int r = 0; r < 3; r++) { // header rows 1-3 (0-2): dummy
                Row header = sheet.createRow(r);
                header.createCell(0).setCellValue("헤더" + r);
                header.createCell(1).setCellValue("수수료");
            }
            sheet.createRow(3); // row 4 (0-based 3) left blank per format
            int rowIdx = 4;
            for (String[] cells : dataRows) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(cells[0]);
                if (cells.length > 1 && cells[1] != null) {
                    row.createCell(1).setCellValue(Double.parseDouble(cells[1]));
                }
            }
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    @Test
    void parse_dedupsByCode_splitsPath_parsesCodeAndFeeFraction() throws IOException {
        List<ParsedLeaf> leaves = parser.parse(xlsx(
                new String[]{"[58646] 식품>가공/즉석식품>라면>봉지라면", "10.6"},
                new String[]{"[58646] 식품>가공/즉석식품>라면>봉지라면", "10.6"}, // option-variant dupe
                new String[]{"[58647] 식품>가공/즉석식품>라면>컵라면", "10.6"},
                new String[]{"[70000] 식품>음료>생수", "7.8"}));

        assertThat(leaves).hasSize(3); // dupe collapsed
        ParsedLeaf bongji = leaves.get(0);
        assertThat(bongji.code()).isEqualTo("58646");
        assertThat(bongji.feeRate()).isEqualByComparingTo(new BigDecimal("0.106")); // percent → fraction
        assertThat(bongji.segments())
                .containsExactly("식품", "가공/즉석식품", "라면", "봉지라면"); // "/" inside a name is preserved
        assertThat(leaves.get(2).segments()).containsExactly("식품", "음료", "생수");
        assertThat(leaves.get(2).feeRate()).isEqualByComparingTo(new BigDecimal("0.078"));
    }

    @Test
    void parse_skipsBracketlessRows_andBlankFee() throws IOException {
        List<ParsedLeaf> leaves = parser.parse(xlsx(
                new String[]{"섹션 제목 (대괄호 없음)"},               // skipped: no code bracket
                new String[]{"[100] 뷰티>스킨케어>토너"}));              // fee blank → null

        assertThat(leaves).hasSize(1);
        assertThat(leaves.get(0).code()).isEqualTo("100");
        assertThat(leaves.get(0).feeRate()).isNull();
    }
}
