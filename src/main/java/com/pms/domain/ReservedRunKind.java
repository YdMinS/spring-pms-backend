package com.pms.domain;

/**
 * 예약 실행 계기 (FEATURE_2609_75 / D15 · D16). DELAYED = 서버가 꺼져 있던 동안 지난 예약.
 * MANUAL = [다시 시도](STOPPED 예약 — 첫 실행이 이미 있어 {@code first_run_kind} 에 저장되지 않는다).
 */
public enum ReservedRunKind {
    ON_TIME, DELAYED, MANUAL
}
