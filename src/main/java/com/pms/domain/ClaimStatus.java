package com.pms.domain;

/**
 * 플랫폼 중립 클레임 상태 (FEATURE_2609_18 / PLAN §3.1 · D3).
 *
 * 화면·필터는 이 정규화 값으로만 동작해 플랫폼이 늘어도 바뀌지 않는다.
 * 원문 상태 코드는 정보 손실 없이 {@link OrderClaim#getPlatformStatus()} 에 그대로 보존한다.
 *
 * <p>{@code IN_PROGRESS}·{@code REJECTED}·{@code WITHDRAWN} 은 교환 전용이라 반품 적재(Stage A)에서는
 * 부여되지 않지만, 교환 어댑터(06)가 같은 enum 을 공유하므로 여기에 함께 둔다.
 * {@code STALE} 은 로컬 전용(D11) — 미완결 추적(05)이 강제 종결에 쓰며 이 단계에서는 부여하지 않는다.
 */
public enum ClaimStatus {
    RECEIVED,
    IN_PROGRESS,
    DONE,
    REJECTED,
    WITHDRAWN,
    PENDING_REVIEW,
    STALE;

    /**
     * 쿠팡 반품 receiptStatus → 정규화 (PLAN §3.1).
     *
     * RU(출고중지요청)·UC(반품접수) → {@code RECEIVED} / CC(반품완료) → {@code DONE} /
     * PR(쿠팡확인요청) → {@code PENDING_REVIEW}.
     * 모르는 값(신규 코드·null)은 가장 안전한 미완결인 {@code RECEIVED} 로 둔다 — 종결로 오분류하면
     * 추적 대상(D7)에서 빠져 영영 갱신되지 않는다.
     */
    public static ClaimStatus fromCoupangReturn(String receiptStatus) {
        if (receiptStatus == null) {
            return RECEIVED;
        }
        return switch (receiptStatus.trim().toUpperCase()) {
            case "CC" -> DONE;
            case "PR" -> PENDING_REVIEW;
            default -> RECEIVED;      // RU, UC 및 미지의 코드
        };
    }

    /** 미완결 = 04·05 의 추적 대상. DONE/REJECTED/WITHDRAWN/STALE 이 아닌 것(PLAN §3.1). */
    public boolean isOpen() {
        return this != DONE && this != REJECTED && this != WITHDRAWN && this != STALE;
    }
}
