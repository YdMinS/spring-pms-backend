package com.pms.service.category;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FEATURE_2610_26 / D14: title row skipped, header found, merged 「대분류」 carried down, a text fee read as null. */
class EsmFeeTableParserTest {

    private final EsmFeeTableParser parser = new EsmFeeTableParser();

    private static byte[] table() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("카테고리별서비스이용료");
            sheet.createRow(0).createCell(0).setCellValue("카테고리별 서비스 이용료");
            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("대분류");
            header.createCell(1).setCellValue("중분류");
            header.createCell(2).setCellValue("오픈마켓");
            Row whole = sheet.createRow(2);
            whole.createCell(0).setCellValue("건강/의료용품");
            whole.createCell(1).setCellValue("전체");
            whole.createCell(2).setCellValue(0.09);
            Row mask = sheet.createRow(3);
            mask.createCell(1).setCellValue("마스크");
            mask.createCell(2).setCellValue(0.11);
            sheet.addMergedRegion(new CellRangeAddress(2, 3, 0, 0));
            Row text = sheet.createRow(4);
            text.createCell(0).setCellValue("상품권");
            text.createCell(1).setCellValue("전체");
            text.createCell(2).setCellValue("별도");
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void parse_readsRowsBelowTheHeader() throws IOException {
        List<EsmFeeRow> rows = parser.parse(table());

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).major()).isEqualTo("건강/의료용품");
        assertThat(rows.get(0).rate()).isEqualByComparingTo("0.09");
        assertThat(rows.get(1).major()).isEqualTo("건강/의료용품");
        assertThat(rows.get(1).middle()).isEqualTo("마스크");
        assertThat(rows.get(1).rate()).isEqualByComparingTo("0.11");
        assertThat(rows.get(2).rate()).isNull();
        assertThat(rows.get(2).rawRate()).isEqualTo("별도");
    }

    @Test
    void parse_withoutHeader_rejected() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("x").createRow(0).createCell(0).setCellValue("다른 표");
            wb.write(out);
            assertThatThrownBy(() -> parser.parse(out.toByteArray()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(EsmFeeTableParser.NO_TABLE);
        }
    }
}
