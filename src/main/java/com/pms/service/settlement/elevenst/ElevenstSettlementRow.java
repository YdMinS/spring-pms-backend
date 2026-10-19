package com.pms.service.settlement.elevenst;

import com.pms.service.elevenst.ElevenstXml;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One row of the 11st settlement list (guide category 151, 「정산내역조회」) — the kept fields and their readers
 * (FEATURE_2610_20 / D16 · D27 · D28). Pure static.
 *
 * <p>🔴 {@link #FIELDS} is an ALLOW list (D16, same rule as the 11st order raw — 2610_17 D43): only these 46 fields
 * are kept, so a field 11st adds later never lands in our tables. Member id / name ({@code memId} · {@code memNm})
 * and list helpers ({@code seqNo} · {@code totalCount}) are not in it.</p>
 */
public final class ElevenstSettlementRow {

    /** D16: the kept fields, in the order of the guide table. */
    public static final List<String> FIELDS = List.of(
            "ordNo", "ordPrdSeq", "dlvNo", "clmReqSeq", "prdNo", "sellerPrdNo", "prdNm", "slctPrdOptNm", "ordQty",
            "feeTypeNm",
            "ordStlEndDt", "pocnfrmDt", "sndEndDt", "trtEndDt", "stlDy", "stlPlnDy",
            "selPrc", "optAmt", "selPrcAmt", "selFee", "selFixedFee", "deductAmt", "stlAmt",
            "cupnAmt", "sellerCupnAmt", "tmallApplyDscAmt", "cardDscSellerDfrmAmt", "sellerDfrmMultiDscCst",
            "sbdscSellerDfrmAmtSum", "sbdscFlctnStlAmt",
            "tmallOverDscAmt",
            "dlvAmt", "bmStlDlvCst", "clmDlvCst", "addClmDlvCst", "clmFstDlvCst", "bmClmFstDlvCst", "abrdCnDlvCst",
            "sellerDfrmAppDlvAmt", "sellerDfrmDeferredAdFee", "sellerDfrmIntfreeFee", "sellerDfrmOcbAmt",
            "sellerDfrmPntPrd", "sellerDfrmGblDlvFee", "sellerDfrmGblCnDlvCst", "debaGtnStlAmt");

    private ElevenstSettlementRow() {
    }

    /** The kept fields of a parsed row, in {@link #FIELDS} order; fields the row does not carry are left out. */
    public static Map<String, String> keep(Map<String, String> row) {
        Map<String, String> kept = new LinkedHashMap<>();
        for (String field : FIELDS) {
            String value = row.get(field);
            if (value != null) {
                kept.put(field, value);
            }
        }
        return kept;
    }

    /** D16: the kept fields as {@code <settlement><field>value</field>…</settlement>}. */
    public static String rawXml(Map<String, String> kept) {
        StringBuilder xml = new StringBuilder("<settlement>");
        for (String field : FIELDS) {
            String value = kept.get(field);
            if (value != null) {
                ElevenstXml.element(xml, field, value);
            }
        }
        return xml.append("</settlement>").toString();
    }

    /** Trimmed text, or null when blank. */
    public static String text(Map<String, String> row, String field) {
        String value = row.get(field);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /** Trimmed text cut to {@code max} characters, or null when blank. */
    public static String text(Map<String, String> row, String field, int max) {
        String value = text(row, field);
        return (value == null || value.length() <= max) ? value : value.substring(0, max);
    }

    /** Amount text with thousands commas removed; blank or unreadable → null (unknown, not 0). */
    public static BigDecimal money(Map<String, String> row, String field) {
        String value = text(row, field);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Date of either 11st shape — {@code YYYYMMDD} (payment / purchase-confirmed dates) or {@code YYYY/MM/DD}
     * (shipping · return · settlement · remittance dates): the digits are read as {@code yyyyMMdd}. Anything else
     * → null.
     */
    public static LocalDate date(Map<String, String> row, String field) {
        String value = text(row, field);
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() != 8) {
            return null;
        }
        try {
            return LocalDate.parse(digits, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Null when every part is null; otherwise the sum of the parts that are present. */
    public static BigDecimal sum(Map<String, String> row, String... fields) {
        BigDecimal total = null;
        for (String field : fields) {
            BigDecimal value = money(row, field);
            if (value != null) {
                total = total == null ? value : total.add(value);
            }
        }
        return total;
    }
}
