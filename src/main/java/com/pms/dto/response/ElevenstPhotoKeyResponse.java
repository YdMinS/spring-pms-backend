package com.pms.dto.response;

/**
 * The company's 11st photo lookup key state (FEATURE_2611_11 / D39). 🔴 The key itself is never returned.
 *
 * @param configured true = a key is stored (「입력됨」), false = none (「비어 있음」)
 */
public record ElevenstPhotoKeyResponse(boolean configured) {
}
