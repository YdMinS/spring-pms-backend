package com.pms.service.elevenst;

import java.net.URLEncoder;

/**
 * Free text that 11st takes as a URL path segment (FEATURE_2610_17 / D39 ①②): reject / hold reasons and the seller
 * return reason are sent in the GET path, EUC-KR percent-encoded (11st's own body charset). Length rule = 1~200
 * characters after trimming.
 */
public final class ElevenstText {

    /** D39 ①: every 11st reason text is required and at most this long. */
    public static final int MAX_REASON_LENGTH = 200;

    private ElevenstText() {
    }

    /**
     * @return the trimmed text, checked for 1~{@value #MAX_REASON_LENGTH} characters
     * @throws IllegalArgumentException blank or too long (→ 400)
     */
    public static String requireReason(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("사유는 필수입니다");
        }
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("사유는 " + MAX_REASON_LENGTH + "자 이하여야 합니다");
        }
        return trimmed;
    }

    /** One path segment, EUC-KR percent-encoded; a space becomes {@code %20} (not the form-style {@code +}). */
    public static String pathSegment(String text) {
        return URLEncoder.encode(text, ElevenstXml.EUC_KR).replace("+", "%20");
    }
}
