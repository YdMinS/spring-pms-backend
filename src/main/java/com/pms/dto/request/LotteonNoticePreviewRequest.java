package com.pms.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Body of {@code POST /api/admin/lotteon/notice-preview} (FEATURE_2610_24 / D56): the master's notice values as the
 * screen currently holds them (saved or not).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LotteonNoticePreviewRequest {

    /** The chosen Coupang notice group ({@code categoryNoticeGroup}); blank → the LotteON-only group or 「기타(재화)」. */
    private String group;

    /** Coupang item name → value, plus {@code LOTTEON:*} keys (D63 ①). */
    private Map<String, String> notices;
}
