package com.pms.dto.response;

/**
 * 입고 1회의 결과 (PLAN 2609_29 D1·D17·D19).
 *
 * <p>⚠️ {@code stockRecorded=false} 는 두 가지 뜻이다 — 음수 정정(D17) 또는 {@code recordStock=false}(D19).
 * 화면 문구가 같으므로 사유를 나누지 않는다. 필드를 쪼개면 화면이 두 갈래로 갈라진다.
 */
public record PurchaseRecordResult(Long purchaseRecordId, boolean stockRecorded) {}
