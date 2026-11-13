package com.pms.service.elevenst;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the master's notice values (Coupang names, entered once) into the 11st product notification
 * (FEATURE_2610_13 / D89 · D97 · D100 · D101 · D102). Used by the 11st adapter (payload) and by the notice
 * preview endpoint (the screens' 11st view) — one place, so the screen shows exactly what is sent.
 *
 * <p>Rules (D89 · D126): a value that is blank or longer than {@link #MAX_LENGTH} characters becomes {@link #REF_TEXT};
 * JOIN rows join the non-blank values in Coupang order with 「 / 」 (all equal → one); SPLIT rows copy one value;
 * 11st-only rows read {@code ELEVENST:{code}} (D101); a group missing from {@link ElevenstNoticeMap} → 「기타
 * 재화」 with every item {@link #REF_TEXT}. Only the master's product-level values are used (D102).</p>
 */
public final class ElevenstNoticeConverter {

    /** D89 ⑧ / §2-25 ①: 11st takes at most 50 characters per notice value. */
    public static final int MAX_LENGTH = 50;
    public static final String REF_TEXT = "상품 상세페이지 참조";
    /** D101: an 11st-only value is stored in the master notice map under this prefix + item code. */
    public static final String OWN_KEY_PREFIX = "ELEVENST:";
    /** JOIN rows are written and read with this separator. */
    private static final String JOIN_SEPARATOR = " / ";

    /** One 11st notice item: {@code ownKey} is set only for an 11st-only item (the map key its value lives under). */
    public record Line(String code, String name, String value, String ownKey) {
    }

    /**
     * @param usedCoupangItems the request's own notice keys that some 11st item reads (the rest are Coupang-only)
     */
    public record Notification(String typeCode, String typeName, List<Line> lines, List<String> usedCoupangItems) {
    }

    /** FEATURE_2611_27 / R11: an 11st notice read back into our vocabulary — {@code coupangGroup} null when none pairs. */
    public record Imported(String coupangGroup, Map<String, String> notices) {
    }

    private ElevenstNoticeConverter() {
    }

    public static String ownKey(String code) {
        return OWN_KEY_PREFIX + code;
    }

    /**
     * @param coupangGroup the master's Coupang notice group ({@code categoryNoticeGroup})
     * @param notices      the master's notice values (Coupang item name → value, plus {@code ELEVENST:*} keys)
     */
    public static Notification convert(String coupangGroup, Map<String, String> notices) {
        Map<String, String> values = notices == null ? Map.of() : notices;
        Optional<ElevenstNoticeMap.Group> found = ElevenstNoticeMap.find(coupangGroup);
        if (found.isEmpty()) {
            List<Line> lines = new ArrayList<>();
            for (ElevenstNoticeMap.Item item : ElevenstNoticeMap.ETC_ITEMS) {
                lines.add(new Line(item.code(), item.name(), REF_TEXT, null));
            }
            return new Notification(ElevenstNoticeMap.ETC_TYPE_CODE, ElevenstNoticeMap.ETC_TYPE_NAME, lines, List.of());
        }
        ElevenstNoticeMap.Group group = found.get();
        List<Line> lines = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (ElevenstNoticeMap.Row row : group.rows()) {
            switch (row.rule()) {
                case SAME, SPLIT -> {
                    String key = matchKey(values, row.coupangItems().get(0));
                    if (key != null) {
                        used.add(key);
                    }
                    String value = fit(key == null ? null : values.get(key));
                    for (ElevenstNoticeMap.Item item : row.items()) {
                        lines.add(new Line(item.code(), item.name(), value, null));
                    }
                }
                case JOIN -> {
                    List<String> parts = new ArrayList<>();
                    for (String coupangItem : row.coupangItems()) {
                        String key = matchKey(values, coupangItem);
                        if (key != null) {
                            used.add(key);
                            String value = values.get(key);
                            if (value != null && !value.isBlank()) {
                                parts.add(value.trim());
                            }
                        }
                    }
                    String joined = parts.isEmpty() ? null
                            : new LinkedHashSet<>(parts).size() == 1 ? parts.get(0) : String.join(JOIN_SEPARATOR, parts);
                    ElevenstNoticeMap.Item item = row.items().get(0);
                    lines.add(new Line(item.code(), item.name(), fit(joined), null));
                }
                case ELEVENST_ONLY -> {
                    ElevenstNoticeMap.Item item = row.items().get(0);
                    String key = ownKey(item.code());
                    lines.add(new Line(item.code(), item.name(), fit(values.get(key)), key));
                }
                case NOT_SENT -> {
                    // Coupang-only item: nothing is sent to 11st
                }
            }
        }
        return new Notification(group.typeCode(), group.typeName(), lines, List.copyOf(used));
    }

    /**
     * FEATURE_2611_27 / D31: an 11st product notification (type + item code → value) in our vocabulary — the reverse
     * of {@link #convert}, same rules as {@code LotteonNoticeConverter.fromLotteon}.
     *
     * <p>SAME → its Coupang item · JOIN → the value split on {@link #JOIN_SEPARATOR}, one part per Coupang item when
     * the counts match, else the whole value under the first Coupang item · SPLIT → the distinct non-blank values
     * joined under its Coupang item · ELEVENST_ONLY and any code outside the group's rows → {@code ELEVENST:{code}} ·
     * NOT_SENT → nothing. A type code outside the table → no Coupang group and no value. Blank values are left
     * out.</p>
     */
    public static Imported fromElevenst(String typeCode, Map<String, String> valuesByCode) {
        Map<String, String> values = valuesByCode == null ? Map.of() : valuesByCode;
        Optional<ElevenstNoticeMap.Group> found = ElevenstNoticeMap.findByTypeCode(typeCode);
        if (found.isEmpty()) {
            return new Imported(null, Map.of());
        }
        Map<String, String> notices = new LinkedHashMap<>();
        Set<String> known = new LinkedHashSet<>();
        for (ElevenstNoticeMap.Row row : found.get().rows()) {
            row.items().forEach(item -> known.add(item.code()));
            switch (row.rule()) {
                case SAME -> putNonBlank(notices, row.coupangItems().get(0), values.get(row.items().get(0).code()));
                case JOIN -> {
                    String value = values.get(row.items().get(0).code());
                    if (value == null || value.isBlank()) {
                        continue;
                    }
                    String[] parts = value.trim().split(JOIN_SEPARATOR, -1);
                    if (parts.length == row.coupangItems().size()) {
                        for (int i = 0; i < parts.length; i++) {
                            putNonBlank(notices, row.coupangItems().get(i), parts[i]);
                        }
                    } else {
                        notices.put(row.coupangItems().get(0), value.trim());
                    }
                }
                case SPLIT -> {
                    Set<String> parts = new LinkedHashSet<>();
                    for (ElevenstNoticeMap.Item item : row.items()) {
                        String value = values.get(item.code());
                        if (value != null && !value.isBlank()) {
                            parts.add(value.trim());
                        }
                    }
                    if (!parts.isEmpty()) {
                        notices.put(row.coupangItems().get(0), String.join(JOIN_SEPARATOR, parts));
                    }
                }
                case ELEVENST_ONLY -> {
                    String code = row.items().get(0).code();
                    putNonBlank(notices, ownKey(code), values.get(code));
                }
                case NOT_SENT -> {
                    // Coupang-only item: 11st has no value for it
                }
            }
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!known.contains(e.getKey())) {
                putNonBlank(notices, ownKey(e.getKey()), e.getValue());
            }
        }
        return new Imported(found.get().coupangGroup(), notices);
    }

    /** The request key whose whitespace-free form equals the table's Coupang item name (D89 ②), or null. */
    private static String matchKey(Map<String, String> values, String coupangItem) {
        String target = ElevenstNoticeMap.compact(coupangItem);
        for (String key : values.keySet()) {
            if (!key.startsWith(OWN_KEY_PREFIX) && ElevenstNoticeMap.compact(key).equals(target)) {
                return key;
            }
        }
        return null;
    }

    /** D89 ⑧: blank or over {@link #MAX_LENGTH} characters → {@link #REF_TEXT}. */
    private static String fit(String value) {
        if (value == null || value.isBlank()) {
            return REF_TEXT;
        }
        String trimmed = value.trim();
        return trimmed.codePointCount(0, trimmed.length()) > MAX_LENGTH ? REF_TEXT : trimmed;
    }

    private static void putNonBlank(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }
}
