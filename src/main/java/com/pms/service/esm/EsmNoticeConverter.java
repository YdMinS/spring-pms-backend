package com.pms.service.esm;

import com.pms.service.elevenst.ElevenstNoticeConverter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the master's notice values (Coupang names, entered once) into the ESM {@code officialNotice}
 * (FEATURE_2610_26 / D16 · D17). Used by the ESM adapter (payload) and by the notice preview endpoint (the screens'
 * 「G마켓 · 옥션」 view, D56) — one place, so the screen shows exactly what is sent.
 *
 * <p>Rules: a value that is present is sent as is with {@code isExtraMark} false; a blank one is sent as
 * {@link #REF_TEXT} with {@code isExtraMark} true (no length cut — ESM states no limit). JOIN rows join the non-blank
 * values in Coupang order with 「 / 」 (all equal → one); ESM-only rows read {@code ESM:{code}} (D17); a group missing
 * from {@link EsmNoticeMap} → 「기타 재화」 with every item {@link #REF_TEXT}. Only the master's product-level values
 * are used (D17).</p>
 */
public final class EsmNoticeConverter {

    public static final String REF_TEXT = "상품 상세페이지 참조";
    /** D17: an ESM-only value is stored in the master notice map under this prefix + item code. */
    public static final String OWN_KEY_PREFIX = "ESM:";

    /**
     * One ESM notice item: {@code extraMark} = ESM's {@code isExtraMark}; {@code ownKey} is set only for an ESM-only
     * item (the map key its value lives under).
     */
    public record Line(String code, String name, String value, boolean extraMark, String ownKey) {
    }

    /**
     * @param usedCoupangItems the request's own notice keys that some ESM item reads (the rest are Coupang-only)
     */
    public record Notification(String groupNo, String groupName, List<Line> lines, List<String> usedCoupangItems) {
    }

    private EsmNoticeConverter() {
    }

    public static String ownKey(String code) {
        return OWN_KEY_PREFIX + code;
    }

    /**
     * @param coupangGroup the master's Coupang notice group ({@code categoryNoticeGroup})
     * @param notices      the master's notice values (Coupang item name → value, plus {@code ESM:*} keys)
     */
    public static Notification convert(String coupangGroup, Map<String, String> notices) {
        Map<String, String> values = notices == null ? Map.of() : notices;
        Optional<EsmNoticeMap.Group> found = EsmNoticeMap.find(coupangGroup);
        if (found.isEmpty()) {
            List<Line> lines = new ArrayList<>();
            for (EsmNoticeMap.Item item : EsmNoticeMap.ETC_ITEMS) {
                lines.add(new Line(item.code(), item.name(), REF_TEXT, true, null));
            }
            return new Notification(EsmNoticeMap.ETC_GROUP_NO, EsmNoticeMap.ETC_GROUP_NAME, lines, List.of());
        }
        EsmNoticeMap.Group group = found.get();
        List<Line> lines = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (EsmNoticeMap.Row row : group.rows()) {
            switch (row.rule()) {
                case SAME -> {
                    String key = matchKey(values, row.coupangItems().get(0));
                    if (key != null) {
                        used.add(key);
                    }
                    lines.add(line(row.item(), key == null ? null : values.get(key), null));
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
                            : new LinkedHashSet<>(parts).size() == 1 ? parts.get(0) : String.join(" / ", parts);
                    lines.add(line(row.item(), joined, null));
                }
                case ESM_ONLY -> {
                    String key = ownKey(row.item().code());
                    lines.add(line(row.item(), values.get(key), key));
                }
                case NOT_SENT -> {
                    // Coupang-only item: nothing is sent to ESM
                }
            }
        }
        return new Notification(group.groupNo(), group.groupName(), lines, List.copyOf(used));
    }

    /** D16: present → the value with {@code isExtraMark} false; blank → {@link #REF_TEXT} with true. */
    private static Line line(EsmNoticeMap.Item item, String value, String ownKey) {
        if (value == null || value.isBlank()) {
            return new Line(item.code(), item.name(), REF_TEXT, true, ownKey);
        }
        return new Line(item.code(), item.name(), value.trim(), false, ownKey);
    }

    /**
     * The request key whose whitespace-free form equals the table's Coupang item name, or null. Platform-only keys
     * ({@code ESM:*}, {@code ELEVENST:*}) are never Coupang names.
     */
    private static String matchKey(Map<String, String> values, String coupangItem) {
        String target = EsmNoticeMap.compact(coupangItem);
        for (String key : values.keySet()) {
            if (!key.startsWith(OWN_KEY_PREFIX) && !key.startsWith(ElevenstNoticeConverter.OWN_KEY_PREFIX)
                    && EsmNoticeMap.compact(key).equals(target)) {
                return key;
            }
        }
        return null;
    }
}
