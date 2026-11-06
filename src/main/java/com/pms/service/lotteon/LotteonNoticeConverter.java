package com.pms.service.lotteon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the master's notice values (Coupang names, entered once) into the LotteON product notice {@code pdItmsInfo}
 * and back (FEATURE_2610_24 / D13 · D14 · D58 · D62 · D63 · D64). Used by the LotteON adapter (payload · product read),
 * by the notice preview endpoint (the screens' LotteON tab, D56) and by the LotteON import (master fill, D64) — one
 * place, so the screen shows exactly what is sent.
 *
 * <p>Outbound (D13 · D63 ③): the Coupang group in {@link LotteonNoticeMap} → its rows (SAME · JOIN · SPLIT ·
 * NOT_SENT, blank value → {@link #REF_TEXT}, no length cut); else a {@link #GROUP_KEY} naming a §2-11 item group → its
 * items from the {@code LOTTEON:{group}:{item}} keys; else item group {@code 38} with every item {@link #REF_TEXT}.
 * Only the master's product-level values are used (D14).</p>
 */
public final class LotteonNoticeConverter {

    public static final String REF_TEXT = "상품 상세페이지 참조";
    /** D63 ①: LotteON-only values are stored in the master notice map under this prefix. */
    public static final String OWN_KEY_PREFIX = "LOTTEON:";
    /** D63 ①: the LotteON item group code of a product no Coupang group pairs with. */
    public static final String GROUP_KEY = OWN_KEY_PREFIX + "품목";
    /** D62 ②: JOIN rows are written and read with this separator. */
    static final String JOIN_SEPARATOR = " / ";

    /** One LotteON notice item: {@code ownKey} is set only for a LotteON-only item (the map key its value lives under). */
    public record Line(String code, String name, String value, String ownKey) {
    }

    /**
     * @param usedCoupangItems the request's own notice keys that some LotteON item reads (the rest are Coupang-only)
     */
    public record Notification(String itemGroupCode, String itemGroupName, List<Line> lines,
                               List<String> usedCoupangItems) {
    }

    /** D58 · D63: a LotteON notice read back into our vocabulary — {@code coupangGroup} null when none pairs. */
    public record Imported(String coupangGroup, Map<String, String> notices) {
    }

    /** D64: the master's notice group and values after filling only what was missing. */
    public record Filled(String group, Map<String, String> notices, boolean changed) {
    }

    private LotteonNoticeConverter() {
    }

    /** D63 ①: the key a §2-11 item value lives under. */
    public static String ownKey(String itemGroupCode, String itemCode) {
        return OWN_KEY_PREFIX + itemGroupCode + ":" + itemCode;
    }

    /**
     * @param coupangGroup the master's Coupang notice group ({@code categoryNoticeGroup})
     * @param notices      the master's notice values (Coupang item name → value, plus {@code LOTTEON:*} keys)
     */
    public static Notification convert(String coupangGroup, Map<String, String> notices) {
        Map<String, String> values = notices == null ? Map.of() : notices;
        Optional<LotteonNoticeMap.Group> found = LotteonNoticeMap.find(coupangGroup);
        if (found.isPresent()) {
            return fromTable(found.get(), values);
        }
        Optional<LotteonNoticeMap.OwnGroup> own = LotteonNoticeMap.findOwn(values.get(GROUP_KEY));
        if (own.isPresent()) {
            List<Line> lines = new ArrayList<>();
            for (LotteonNoticeMap.Item item : own.get().items()) {
                String key = ownKey(own.get().itemGroupCode(), item.code());
                lines.add(new Line(item.code(), item.name(), fit(values.get(key)), key));
            }
            return new Notification(own.get().itemGroupCode(), own.get().itemGroupName(), lines, List.of());
        }
        List<Line> lines = new ArrayList<>();
        for (LotteonNoticeMap.Item item : LotteonNoticeMap.ETC_ITEMS) {
            lines.add(new Line(item.code(), item.name(), REF_TEXT, null));
        }
        return new Notification(LotteonNoticeMap.ETC_GROUP_CODE, LotteonNoticeMap.ETC_GROUP_NAME, lines, List.of());
    }

    private static Notification fromTable(LotteonNoticeMap.Group group, Map<String, String> values) {
        List<Line> lines = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (LotteonNoticeMap.Row row : group.rows()) {
            switch (row.rule()) {
                case SAME, SPLIT -> {
                    String key = matchKey(values, row.coupangItems().get(0));
                    if (key != null) {
                        used.add(key);
                    }
                    String value = fit(key == null ? null : values.get(key));
                    for (LotteonNoticeMap.Item item : row.items()) {
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
                            : new LinkedHashSet<>(parts).size() == 1 ? parts.get(0)
                            : String.join(JOIN_SEPARATOR, parts);
                    LotteonNoticeMap.Item item = row.items().get(0);
                    lines.add(new Line(item.code(), item.name(), fit(joined), null));
                }
                case NOT_SENT -> {
                    // Coupang-only item: nothing is sent to LotteON
                }
            }
        }
        return new Notification(group.itemGroupCode(), group.itemGroupName(), lines, List.copyOf(used));
    }

    /**
     * D58 · D62 · D63: a LotteON answer ({@code pdItmsCd} + item code → value) in our vocabulary. A paired item group
     * → its Coupang group and Coupang item values (D62 ① ~ ⑤; an item outside the table → its {@code LOTTEON:} key,
     * ⑥); a §2-11 item group → no Coupang group, {@link #GROUP_KEY} + every value under its {@code LOTTEON:} key;
     * any other code → nothing. Blank values are left out.
     */
    public static Imported fromLotteon(String itemGroupCode, Map<String, String> valuesByItemCode) {
        Map<String, String> values = valuesByItemCode == null ? Map.of() : valuesByItemCode;
        Map<String, String> notices = new LinkedHashMap<>();
        Optional<LotteonNoticeMap.Group> found = LotteonNoticeMap.findByItemGroup(itemGroupCode);
        if (found.isPresent()) {
            Set<String> known = new LinkedHashSet<>();
            for (LotteonNoticeMap.Row row : found.get().rows()) {
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
                        for (LotteonNoticeMap.Item item : row.items()) {
                            String value = values.get(item.code());
                            if (value != null && !value.isBlank()) {
                                parts.add(value.trim());
                            }
                        }
                        if (!parts.isEmpty()) {
                            notices.put(row.coupangItems().get(0), String.join(JOIN_SEPARATOR, parts));
                        }
                    }
                    case NOT_SENT -> {
                        // D62 ⑤: LotteON has no value for this Coupang item
                    }
                }
            }
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (!known.contains(e.getKey())) {
                    putNonBlank(notices, ownKey(itemGroupCode, e.getKey()), e.getValue());
                }
            }
            return new Imported(found.get().coupangGroup(), notices);
        }
        if (LotteonNoticeMap.findOwn(itemGroupCode).isPresent()) {
            notices.put(GROUP_KEY, itemGroupCode);
            for (Map.Entry<String, String> e : values.entrySet()) {
                putNonBlank(notices, ownKey(itemGroupCode, e.getKey()), e.getValue());
            }
        }
        return new Imported(null, notices);
    }

    /**
     * D64: fill only what the master is missing. ① a key is missing when absent, null or blank. ② the Coupang group
     * is set when the master has none; Coupang item values are filled only when the master's group (after that) is the
     * imported one. ③ {@link #GROUP_KEY} is set when missing; {@code LOTTEON:{group}:{item}} values are filled only
     * for the master's own {@link #GROUP_KEY}.
     */
    public static Filled fillMissing(String masterGroup, Map<String, String> masterNotices, Imported imported) {
        Map<String, String> merged = masterNotices == null ? new LinkedHashMap<>() : new LinkedHashMap<>(masterNotices);
        String group = blank(masterGroup) ? null : masterGroup.trim();
        boolean changed = false;
        if (group == null && imported.coupangGroup() != null) {
            group = imported.coupangGroup();
            changed = true;
        }
        boolean sameGroup = group != null && imported.coupangGroup() != null
                && LotteonNoticeMap.compact(group).equals(LotteonNoticeMap.compact(imported.coupangGroup()));
        String importedOwnGroup = imported.notices().get(GROUP_KEY);
        if (blank(merged.get(GROUP_KEY)) && !blank(importedOwnGroup)) {
            merged.put(GROUP_KEY, importedOwnGroup);
            changed = true;
        }
        String ownPrefix = blank(merged.get(GROUP_KEY)) ? null : OWN_KEY_PREFIX + merged.get(GROUP_KEY).trim() + ":";
        for (Map.Entry<String, String> e : imported.notices().entrySet()) {
            String key = e.getKey();
            if (key.equals(GROUP_KEY) || blank(e.getValue()) || !blank(merged.get(key))) {
                continue;
            }
            boolean own = key.startsWith(OWN_KEY_PREFIX);
            if ((own && ownPrefix != null && key.startsWith(ownPrefix)) || (!own && sameGroup)) {
                merged.put(key, e.getValue());
                changed = true;
            }
        }
        return new Filled(group, merged, changed);
    }

    /** The request key whose whitespace-free form equals the table's Coupang item name (11st D89 ②), or null. */
    private static String matchKey(Map<String, String> values, String coupangItem) {
        String target = LotteonNoticeMap.compact(coupangItem);
        for (String key : values.keySet()) {
            if (!key.startsWith(OWN_KEY_PREFIX) && LotteonNoticeMap.compact(key).equals(target)) {
                return key;
            }
        }
        return null;
    }

    /** D13: blank → {@link #REF_TEXT}; LotteON takes up to 4000 characters, so nothing is cut. */
    private static String fit(String value) {
        return value == null || value.isBlank() ? REF_TEXT : value.trim();
    }

    private static void putNonBlank(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
