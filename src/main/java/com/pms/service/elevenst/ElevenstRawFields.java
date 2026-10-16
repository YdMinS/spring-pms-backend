package com.pms.service.elevenst;

import com.pms.domain.ElevenstOrderLine;
import org.w3c.dom.Document;

/**
 * Reads one field of an 11st order line's {@code raw} (the D43 allow-listed XML) — FEATURE_2610_17 / D64 · D66.
 * Only the allow-listed fields exist there; anything else reads as null.
 */
public final class ElevenstRawFields {

    private ElevenstRawFields() {
    }

    /** The trimmed text of {@code field}, or null when the raw XML is missing, unreadable or has no such field. */
    public static String field(ElevenstOrderLine line, String field) {
        if (line == null || line.getRaw() == null || line.getRaw().isBlank()) {
            return null;
        }
        Document doc = ElevenstXml.parseOrNull(line.getRaw().getBytes(ElevenstXml.EUC_KR));
        String value = ElevenstXml.firstText(doc, field);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /** True when {@code field} is {@code Y} (11st yes/no flags such as {@code visitDlvYn} · {@code addPrdYn}). */
    public static boolean yes(ElevenstOrderLine line, String field) {
        return "Y".equalsIgnoreCase(field(line, field));
    }
}
