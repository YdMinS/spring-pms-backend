package com.pms.service.esm;

import java.nio.charset.Charset;

/**
 * ESM byte-length rule (FEATURE_2610_26 / D20 · D23 · D26): a text is cut so that it fits a byte limit, Korean
 * counting 2 bytes — the EUC-KR count the 11st rule uses (11st D82). Nothing is replaced (ESM lists no forbidden
 * character).
 */
public final class EsmText {

    /** D20: {@code goodsName.kor} limit. */
    public static final int GOODS_NAME_BYTES = 100;
    /** D23 · D26: option group name and option value limit. */
    public static final int OPTION_NAME_BYTES = 50;

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    private EsmText() {
    }

    /** {@code text} cut at {@code maxBytes} EUC-KR bytes, never in the middle of a character; null stays null. */
    public static String cutBytes(String text, int maxBytes) {
        if (text == null) {
            return null;
        }
        StringBuilder cut = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int size = ch.getBytes(EUC_KR).length;
            if (bytes + size > maxBytes) {
                break;
            }
            cut.append(ch);
            bytes += size;
            i += Character.charCount(cp);
        }
        return cut.toString();
    }
}
