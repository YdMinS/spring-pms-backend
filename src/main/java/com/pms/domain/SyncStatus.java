package com.pms.domain;

/**
 * 채널(계정) 단위 마지막 동기화 결과 (FEATURE_2609_02 / PLAN D6·D8·D18).
 *
 * <ul>
 *   <li>{@code SUCCESS} — 주문 조회 전 상태 성공 + 취소 보정 성공</li>
 *   <li>{@code PARTIAL} — 주문 조회 일부 상태 실패(D18) 또는 취소 보정 실패(D8).
 *       취소 보정이 빠지면 이미 취소된 주문이 발주가능으로 보이므로 성공과 같이 취급하면 안 된다.</li>
 *   <li>{@code FAILED} — 주문 조회가 통째로 실패(예외). 취소 보정은 실행되지 않았다.</li>
 * </ul>
 *
 * 상태값은 이 3개로 고정한다 — 부분 실패의 종류는 상태를 늘리지 않고
 * {@code MarketplaceAccount.lastSyncError} 사유 문자열로 구분한다(D18).
 */
public enum SyncStatus {
    SUCCESS,
    PARTIAL,
    FAILED
}
