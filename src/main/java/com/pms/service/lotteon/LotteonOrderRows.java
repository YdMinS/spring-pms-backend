package com.pms.service.lotteon;

import com.fasterxml.jackson.databind.JsonNode;
import com.pms.domain.OrderStatus;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Reads one LotteON order row ([209] · [140]) — FEATURE_2611_23 / D7 · D8. Shared by the upserter, the confirmation,
 * the sheets, the invoice work and the delivery-record list (D19).
 */
public final class LotteonOrderRows {

    private LotteonOrderRows() {
    }

    /** D8: a delivery ({@code dvRtrvDvsCd} DV) line of an order ({@code odTypCd} 10). */
    public static boolean isOrderDelivery(JsonNode row) {
        return "DV".equals(text(row, "dvRtrvDvsCd")) && "10".equals(text(row, "odTypCd"));
    }

    /**
     * D19 ②: the delivery records of one order line — the {@link #isOrderDelivery} rows of {@code odSeq}, in
     * {@code procSeq} numeric order (R17). Used by the invoice update and the record list.
     */
    public static List<JsonNode> shipmentRecords(List<JsonNode> rows, String odSeq) {
        if (odSeq == null) {
            return List.of();
        }
        return rows.stream()
                .filter(LotteonOrderRows::isOrderDelivery)
                .filter(row -> odSeq.equals(text(row, "odSeq")))
                .sorted(Comparator.comparing(row -> sequence(text(row, "procSeq"))))
                .toList();
    }

    /** R17: [140] {@code odPrgsStepCd} → LotteON's own step name; a code not in the table is returned as is. */
    public static String stepName(String step) {
        if (step == null) {
            return null;
        }
        return switch (step) {
            case "11" -> "출고지시";
            case "12" -> "상품준비";
            case "13" -> "발송완료";
            case "14" -> "배송완료";
            case "15" -> "수취완료";
            case "21" -> "취소완료";
            case "22" -> "철회";
            default -> step;
        };
    }

    /** {@code procSeq} as a number for ordering; blank or not a number sorts last. */
    private static BigDecimal sequence(String value) {
        if (value == null) {
            return BigDecimal.valueOf(Long.MAX_VALUE);
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.valueOf(Long.MAX_VALUE);
        }
    }

    /** D8: {@code odPrgsStepCd} → neutral status; anything else → null (never loaded). */
    public static OrderStatus status(String step) {
        if (step == null) {
            return null;
        }
        return switch (step) {
            case "11" -> OrderStatus.PAID;
            case "12" -> OrderStatus.PREPARING;
            case "13" -> OrderStatus.SHIPPED;
            case "14", "15" -> OrderStatus.DELIVERED;
            default -> null;
        };
    }

    /**
     * D7: delivery bundle id — {@code odNo-G{cmbnDvGrpNo}} when combined delivery is possible and a group number is
     * present (e.g. {@code OD1-G3}), else {@code odNo-odSeq} (e.g. {@code OD1-1}).
     */
    public static String bundleId(JsonNode row) {
        String odNo = text(row, "odNo");
        String group = text(row, "cmbnDvGrpNo");
        if ("Y".equals(text(row, "cmbnDvPsbYn")) && group != null) {
            return odNo + "-G" + group;
        }
        return odNo + "-" + text(row, "odSeq");
    }

    /** Field text: absent · null · blank → null; a number without a fraction is written without one; trimmed. */
    public static String text(JsonNode row, String field) {
        if (row == null) {
            return null;
        }
        JsonNode node = row.get(field);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String value;
        if (node.isNumber()) {
            value = node.isIntegralNumber()
                    ? node.bigIntegerValue().toString()
                    : node.decimalValue().stripTrailingZeros().toPlainString();
        } else {
            value = node.asText();
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    /** Amount without thousands separators; unreadable → null (unknown, not 0). */
    public static BigDecimal money(JsonNode row, String field) {
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

    /** Whole quantity of {@link #money}; null → 0. */
    public static int quantity(JsonNode row, String field) {
        BigDecimal value = money(row, field);
        return value == null ? 0 : value.intValue();
    }
}
