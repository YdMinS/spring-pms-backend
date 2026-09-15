package com.pms.service.coupang;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 주문 동기화 단일 진입점 (★ 중복 방지).
 *
 * 앱 진입·화면 새로고침·스케줄러 등 모든 동기화 호출은 반드시 이 facade 한 곳을 거친다.
 * 화면/호출부마다 ordersheets+returnRequests 묶음 로직을 복제하지 말 것(CLAUDE.md 공통 서비스 규칙).
 *
 * 순서 고정: ordersheets upsert(신규/갱신) → returnRequests 취소 보정. 계정 단위 격리(한 계정 실패가
 * 전체를 롤백하지 않음, 단 단건 sync(accountId)는 예외 전파).
 */
public interface OrderSyncFacade {

    /** 계정 1개 동기화(전 상태). 없는 계정이면 ResourceNotFoundException. */
    OrderSyncResult sync(Long accountId);

    /**
     * 계정 1개를 <b>지정 범위</b>로 동기화 (FEATURE_2609_16).
     *
     * 달라지는 건 <b>조회할 주문 상태뿐</b>이다 — 취소 보정(returnRequests)과 동기화 상태 기록은
     * {@link #sync(Long)} 과 완전히 동일하게 돈다(PLAN 2609_16 D5·D6). 출고관리처럼 종결 상태가
     * 필요 없는 화면이 {@link OrderSyncScope#ACTIVE} 로 쿠팡 왕복을 6 → 2 로 줄이는 자리다.
     */
    OrderSyncResult sync(Long accountId, OrderSyncScope scope);

    /** 한 셀러의 활성 COUPANG 계정 전체 동기화 (계정 단위 격리). */
    OrderSyncResult syncBySeller(Long sellerId);

    /** 모든 셀러의 활성 COUPANG 계정 전체 동기화 (계정 단위 격리). */
    OrderSyncResult syncAll();

    /**
     * 지정 기간을 계정 1건에 대해 불러온다 (과거 기간 백필, FEATURE_2609_10).
     *
     * 정기 동기화({@link #sync})와 다른 점 — 의도된 차이다(PLAN D4·D5):
     * - 취소 보정(returnRequests)을 실행하지 않는다 → 결과의 canceledUpdated 는 항상 0
     * - SyncStatusRecorder 를 갱신하지 않는다 → "마지막 동기화" 배너를 과거 백필이 덮어쓰지 않는다
     *
     * 실패는 그대로 전파한다(계정 단위 격리는 호출자인 프론트의 순차 루프가 담당, D9).
     */
    OrderSyncResult syncPeriod(Long accountId, LocalDate from, LocalDate to);

    /** 동기화 결과 집계 (신규/갱신 주문 수 + 취소 보정 수 + 건너뛴 채널 수). */
    record OrderSyncResult(LocalDateTime syncedAt, int newOrders, int updatedOrders, int canceledUpdated,
                           int skippedAccounts) {

        /**
         * 이미 같은 채널이 돌고 있어 쿠팡을 치지 않은 회차(FEATURE_2609_48 / D5).
         * 실패가 아니라 "지금 하는 중"이다.
         *
         * <p>🔴 {@code syncedAt} 이 {@code null} 인 이유(D9): 이 회차는 조회를 하지 않았다.
         * {@code now()} 를 실으면 {@link #plus} 가 그것을 취해, 전체 동기화의 마지막 계정이
         * 건너뛰었을 때 합계 시각이 실제로 조회하지 않은 시각으로 덮인다.
         */
        static OrderSyncResult skipped() {
            return new OrderSyncResult(null, 0, 0, 0, 1);
        }

        /** 🔴 씨앗도 {@code null} 이다(D9) — 전 채널이 건너뛰면 여기 실은 시각이 그대로 합계로 남는다. */
        public static OrderSyncResult empty() {
            return new OrderSyncResult(null, 0, 0, 0, 0);
        }

        public OrderSyncResult plus(OrderSyncResult other) {
            return new OrderSyncResult(
                    // 🔴 실제로 조회한 시각만 남긴다(D9). 건너뛴 회차는 시각이 없으므로 앞의 값을 유지한다.
                    other.syncedAt != null ? other.syncedAt : syncedAt,
                    newOrders + other.newOrders,
                    updatedOrders + other.updatedOrders,
                    canceledUpdated + other.canceledUpdated,
                    skippedAccounts + other.skippedAccounts);
        }
    }
}
