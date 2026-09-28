package com.pms.domain;

/** 예약 발송 1건의 상태 (FEATURE_2609_75 / D5 · D16). */
public enum ReservedShipmentStatus {
    SCHEDULED, RUNNING, DONE, STOPPED, CANCELLED
}
