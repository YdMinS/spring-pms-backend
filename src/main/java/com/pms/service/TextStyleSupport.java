package com.pms.service;

import java.util.Map;
import java.util.function.UnaryOperator;

/** Package-private central registry: text 블록 inline style key → (CSS prop, value sanitizer). 새 속성 = 여기 1엔트리. */
final class TextStyleSupport {
    private TextStyleSupport() {}

    record StyleProp(String css, UnaryOperator<String> sanitize) {}

    static final Map<String, StyleProp> REGISTRY = Map.of(
        "fontSize", new StyleProp("font-size",  v -> intPx(v, 8, 200)),  // "18" → "18px"
        "color",    new StyleProp("color",      TextStyleSupport::hex),  // "#ff0000" 만 통과
        "bold",     new StyleProp("font-weight", v -> "true".equals(v) ? "700" : null),
        "italic",   new StyleProp("font-style",  v -> "true".equals(v) ? "italic" : null)
    );

    /** Emit `cssProp:sanitizedValue;` for every registered, valid entry. Unknown key / null value / null sanitize → skip. */
    static String toCss(Map<String, String> style) {
        if (style == null || style.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var e : style.entrySet()) {
            StyleProp p = REGISTRY.get(e.getKey());
            if (p == null) continue;
            String v = p.sanitize().apply(e.getValue());   // sanitizer 는 null 입력 방어 필수 (아래 hex/intPx 참고)
            if (v != null) sb.append(p.css()).append(':').append(v).append(';');
        }
        return sb.toString();
    }

    /** #RRGGBB 6자리만 통과(3자리 허용 시 정규식 확장); null/그 외 → null. */
    static String hex(String v) {
        return v != null && v.matches("^#[0-9a-fA-F]{6}$") ? v : null;
    }

    /** 엄격 정수 파싱 → 클램프 후 "Npx". null·"18px"·"18;color:red" 등 접미사 입력은 parseInt 실패 → null(드롭). */
    static String intPx(String v, int min, int max) {
        if (v == null) return null;
        try {
            int n = Integer.parseInt(v.trim());   // 접미사/구분자 있으면 NumberFormatException → 드롭
            return Math.max(min, Math.min(max, n)) + "px";
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
