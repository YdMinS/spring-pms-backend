package com.pms.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Body of {@code POST /api/admin/elevenst/notice-preview} (FEATURE_2610_13 / D97 · D99 · D100): the master's
 * notice values as the screen currently holds them (saved or not).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ElevenstNoticePreviewRequest {

    /** The chosen Coupang notice group ({@code categoryNoticeGroup}); blank → 「기타 재화」. */
    private String group;

    /** Coupang item name → value, plus {@code ELEVENST:{code}} keys (D101). */
    private Map<String, String> notices;
}
