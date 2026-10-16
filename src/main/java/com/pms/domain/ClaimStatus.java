package com.pms.domain;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

/**
 * Platform-neutral claim status (FEATURE_2609_18 / PLAN §3.1 · D3).
 *
 * Screens and filters work on this normalized value only, so they do not change when a platform is added.
 * The raw status code is kept losslessly in {@link OrderClaim#getPlatformStatus()}.
 *
 * <p>{@code REJECTED}: Coupang gives it to exchanges only; 11st gives it to returns too (107 「반품거부」, FEATURE_2610_17 /
 * D25). {@code IN_PROGRESS} (received / on hold) and {@code WITHDRAWN} (withdrawn, closed) apply to returns as well
 * (2609_21/01). {@code STALE} is local only (D11) — the forced close of open-claim tracking (05).
 */
@Slf4j
public enum ClaimStatus {
    RECEIVED,
    IN_PROGRESS,
    DONE,
    REJECTED,
    WITHDRAWN,
    PENDING_REVIEW,
    STALE;

    /**
     * 쿠팡 반품 receiptStatus → 정규화 (PLAN §3.1 · 2609_21/01).
     *
     * 응답의 실제값은 긴 코드다 — RELEASE_STOP_UNCHECKED(출고중지요청)·RETURNS_UNCHECKED(반품접수) →
     * {@code RECEIVED} / VENDOR_WAREHOUSE_CONFIRM(입고완료) → {@code IN_PROGRESS} /
     * RETURNS_COMPLETED(반품완료) → {@code DONE} / REQUEST_COUPANG_CHECK(쿠팡확인요청) → {@code PENDING_REVIEW}.
     * 요청 파라미터의 단축 코드(RU/UC/CC/PR)도 하위호환으로 함께 받는다 — 과거에 저장된 행이나 다른
     * 조회 경로가 단축 코드를 줄 가능성을 배제할 수 없다.
     * 모르는 값(신규 코드·null)은 가장 안전한 미완결인 {@code RECEIVED} 로 둔다 — 종결로 오분류하면
     * 추적 대상(D7)에서 빠져 영영 갱신되지 않는다.
     *
     * <p>⚠️ 기존 행 백필은 하지 않는다: 원문이 {@code platform_status} 에 온전히 남아 있고 미완결 건은
     * 추적 슬라이스(05)가 다음 회차에 다시 읽어 upsert 로 갱신한다(자연 치유). 이미 {@code STALE} 로 굳은
     * 건만 안 고쳐지는데, 그건 백필 배치를 만들 가치보다 적다.
     */
    public static ClaimStatus fromCoupangReturn(String receiptStatus) {
        if (receiptStatus == null) {
            return RECEIVED;
        }
        return switch (receiptStatus.trim().toUpperCase()) {
            case "RETURNS_COMPLETED", "CC" -> DONE;
            case "REQUEST_COUPANG_CHECK", "PR" -> PENDING_REVIEW;
            case "VENDOR_WAREHOUSE_CONFIRM" -> IN_PROGRESS;
            case "RELEASE_STOP_UNCHECKED", "RETURNS_UNCHECKED", "RU", "UC" -> RECEIVED;
            default -> {
                // 문서에 없는 값이 오면 알아야 한다 — 액션(02)의 화이트리스트가 이 원문으로 판정한다.
                log.warn("Unknown Coupang return receiptStatus: {}", receiptStatus);
                yield RECEIVED;
            }
        };
    }

    /**
     * 쿠팡 교환 receiptStatus → 정규화 (PLAN §3.1).
     *
     * RECEIPT(접수) → {@code RECEIVED} / PROGRESS(진행중) → {@code IN_PROGRESS} /
     * SUCCESS(완료) → {@code DONE} / REJECT(거부) → {@code REJECTED} / CANCEL(철회) → {@code WITHDRAWN}.
     * 모르는 값(신규 코드·null)은 {@link #fromCoupangReturn} 과 같은 판단으로 {@code RECEIVED} 다 —
     * 종결로 오분류하면 추적 대상(D7)에서 빠져 영영 갱신되지 않는다.
     */
    public static ClaimStatus fromCoupangExchange(String receiptStatus) {
        if (receiptStatus == null) {
            return RECEIVED;
        }
        return switch (receiptStatus.trim().toUpperCase()) {
            case "PROGRESS" -> IN_PROGRESS;
            case "SUCCESS" -> DONE;
            case "REJECT" -> REJECTED;
            case "CANCEL" -> WITHDRAWN;
            default -> RECEIVED;      // RECEIPT 및 미지의 코드
        };
    }

    /** 미완결 = 04·05 의 추적 대상. DONE/REJECTED/WITHDRAWN/STALE 이 아닌 것(PLAN §3.1). */
    public boolean isOpen() {
        return this != DONE && this != REJECTED && this != WITHDRAWN && this != STALE;
    }

    /**
     * 종결 상태 집합 — 미완결 추적(05)의 조회 인자.
     *
     * ⚠️ 목록을 손으로 나열하지 말 것. {@link #isOpen()} 에서 파생해야 상태가 늘어도 "무엇이 종결인가"의
     * 정의가 두 벌이 되지 않는다.
     */
    public static List<ClaimStatus> closedStatuses() {
        return Arrays.stream(values()).filter(status -> !status.isOpen()).toList();
    }
}
