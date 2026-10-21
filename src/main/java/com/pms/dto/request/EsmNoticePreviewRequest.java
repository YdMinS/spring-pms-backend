package com.pms.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Body of {@code POST /api/admin/esm/notice-preview} (FEATURE_2610_26 / D56): the master's notice values as the
 * screen currently holds them (saved or not).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class EsmNoticePreviewRequest {

    /** The chosen Coupang notice group ({@code categoryNoticeGroup}); blank → 「기타 재화」. */
    private String group;

    /** Coupang item name → value, plus {@code ESM:{code}} keys (D17). */
    private Map<String, String> notices;
}
