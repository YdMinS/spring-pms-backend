package com.pms.service.category;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the LotteON commission CSV (FEATURE_2610_24 / D46): UTF-8 (a leading BOM is skipped), header
 * {@value #HEADER}, one line per standard category = depth, quoted path, percent (VAT included).
 */
@Component
public class LotteonFeeCsvParser {

    static final String HEADER = "depth,category_path,commission_pct_vat_incl";
    static final String HEADER_MISMATCH = "롯데ON 수수료 파일의 머리줄이 다릅니다: " + HEADER;
    static final String BAD_LINE = "롯데ON 수수료 파일을 읽지 못한 줄: ";

    public List<LotteonFeeRow> parse(byte[] csv) {
        String text = new String(csv, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\r?\n");
        if (lines.length == 0 || !HEADER.equals(lines[0].trim())) {
            throw new IllegalArgumentException(HEADER_MISMATCH);
        }
        List<LotteonFeeRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            int first = line.indexOf(',');
            int last = line.lastIndexOf(',');
            if (first < 0 || last <= first) {
                throw new IllegalArgumentException(BAD_LINE + line);
            }
            String path = line.substring(first + 1, last).trim();
            if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
                path = path.substring(1, path.length() - 1).replace("\"\"", "\"");
            }
            try {
                rows.add(new LotteonFeeRow(path, new BigDecimal(line.substring(last + 1).trim())));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(BAD_LINE + line);
            }
        }
        return rows;
    }
}
