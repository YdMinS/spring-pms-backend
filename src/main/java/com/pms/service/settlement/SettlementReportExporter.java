package com.pms.service.settlement;

import com.pms.dto.response.ReconLineView;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 차이 리포트 ①의 라인 목록 → xlsx (FEATURE_2609_30 / 02 · PLAN D18).
 *
 * <p><b>필수 규칙</b>: 엑셀 생성은 {@code ShippingLabelServiceImpl.toXlsx} 의 {@code XSSFWorkbook} 패턴을
 * 그대로 쓴다 — 새 엑셀 유틸을 만들지 않는다. 컬럼은 화면의 라인 목록과 <b>같은 순서·같은 값</b>이어야 한다
 * (화면과 파일이 다르면 사용자가 어느 쪽을 들고 문의해야 할지 모른다).
 *
 * <p>⚠️ 내보내기 전용이다. 업로드 파서는 이번 스코프가 아니다(D18) — 실제 미분류가 얼마나 나오는지 보고
 * 결정한다.
 */
@Component
public class SettlementReportExporter {

    static final String[] HEADERS = {
            "주문번호", "옵션ID", "상품명", "인식일", "지급일", "정산유형",
            "판매금액", "수수료", "실수수료율", "정산액", "차액", "원인"};

    public byte[] toXlsx(List<ReconLineView> lines) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("정산대사");

            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADERS.length; c++) {
                header.createCell(c).setCellValue(HEADERS[c]);
            }

            int r = 1;
            for (ReconLineView line : lines) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(text(line.externalOrderId()));
                row.createCell(1).setCellValue(text(line.platformOptionId()));
                row.createCell(2).setCellValue(text(line.productName()));
                row.createCell(3).setCellValue(text(line.recognitionDate()));
                row.createCell(4).setCellValue(text(line.settlementDate()));
                row.createCell(5).setCellValue(text(line.settlementType()));
                setNumber(row, 6, line.saleAmount());
                setNumber(row, 7, line.serviceFee());
                setNumber(row, 8, line.serviceFeeRatio());
                setNumber(row, 9, line.settlementAmount());
                setNumber(row, 10, line.diff());
                row.createCell(11).setCellValue(text(line.label()));
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("정산 대사 리포트 xlsx 생성 실패", e);
        }
    }

    /** null 은 빈 셀로 둔다 — 0 으로 채우면 "모른다"와 "0원"이 구분되지 않는다. */
    private static void setNumber(Row row, int column, BigDecimal value) {
        if (value != null) {
            row.createCell(column).setCellValue(value.doubleValue());
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String text(LocalDate value) {
        return value == null ? "" : value.toString();
    }
}
