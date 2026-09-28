package com.pms.domain;

/** 예약 결과 1행이 <b>마지막으로 성공한</b> 단계 (FEATURE_2609_75 / D3 · D16 — 재시도는 다음 단계부터). */
public enum ReservedItemProgress {
    NONE, CANCEL_CHECKED, ACKNOWLEDGED, INVOICED
}
