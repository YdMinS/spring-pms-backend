package com.pms.service.category;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the ESM+ fee table file 「카테고리별서비스이용료_YYMMDD.xlsx」 (FEATURE_2610_26 / D14 · §2-12): the first
 * sheet, the header row whose first cell is 「대분류」, then one {@link EsmFeeRow} per non-empty row below it. Columns
 * A 「대분류」 · B 「중분류」 · C 「오픈마켓」. A blank A cell (the lower cells of a merged 「대분류」) takes the last
 * 「대분류」 above it.
 */
@Component
public class EsmFeeTableParser {

    static final String HEADER_MAJOR = "대분류";
    static final String NO_TABLE = "G마켓 · 옥션 수수료 안내 표를 찾지 못했습니다.";

    private final DataFormatter formatter = new DataFormatter();

    public List<EsmFeeRow> parse(byte[] xlsx) {
        List<EsmFeeRow> rows = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            boolean inTable = false;
            String major = null;
            for (Row row : sheet) {
                String a = text(row.getCell(0));
                String b = text(row.getCell(1));
                if (!inTable) {
                    inTable = HEADER_MAJOR.equals(a);
                    continue;
                }
                if (a.isEmpty() && b.isEmpty()) {
                    continue;
                }
                if (!a.isEmpty()) {
                    major = a;
                }
                Cell rateCell = row.getCell(2);
                BigDecimal rate = rateCell != null && rateCell.getCellType() == CellType.NUMERIC
                        ? BigDecimal.valueOf(rateCell.getNumericCellValue()) : null;
                rows.add(new EsmFeeRow(major, b, rate, text(rateCell)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the ESM fee table file", e);
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(NO_TABLE);
        }
        return rows;
    }

    private String text(Cell cell) {
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }
}
