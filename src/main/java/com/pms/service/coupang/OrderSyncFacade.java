package com.pms.service.coupang;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The single entry of the order sync (★ no duplicates).
 *
 * Every sync call — app start, screen refresh, scheduler — goes through this facade only. Do not copy the
 * ordersheets + returnRequests bundle logic per screen or caller (CLAUDE.md common-service rule).
 *
 * Fixed order: orders upsert (new / update) → cancel correction (Coupang returnRequests · 11st cancel lists,
 * FEATURE_2610_17 / D11). Isolated per account (one account failing does not roll back the rest; only the single
 * sync(accountId) propagates the exception).
 */
public interface OrderSyncFacade {

    /**
     * 계정 1개 동기화. 없는 계정이면 ResourceNotFoundException.
     *
     * <p>{@link OrderSyncPreset#QUICK} 으로 위임한다(FEATURE_2609_49 / D6) — 사용자 대기 경로의
     * 기본값은 좁은 조회다. 전 상태 리컨실은 스케줄러({@link #syncAll(OrderSyncPreset)})가 돈다.
     */
    OrderSyncResult sync(Long accountId);

    /**
     * 계정 1개를 <b>지정 프리셋</b>으로 동기화 (FEATURE_2609_49 / D7).
     *
     * 달라지는 건 <b>주문 조회의 상태 집합과 창뿐</b>이다 — 취소 보정(returnRequests)·클레임·문의
     * 단계와 동기화 상태 기록은 프리셋과 무관하게 동일하게 돈다(D6·D7).
     */
    OrderSyncResult sync(Long accountId, OrderSyncPreset preset);

    /** Syncs every active COUPANG / ELEVENST account of one seller (isolated per account). Always {@link OrderSyncPreset#QUICK}. */
    OrderSyncResult syncBySeller(Long sellerId);

    /** Syncs every active COUPANG / ELEVENST account of every seller (isolated per account). Always {@link OrderSyncPreset#QUICK}. */
    OrderSyncResult syncAll();

    /**
     * Syncs every active COUPANG account — and ELEVENST account, unless 11st calls are switched off on this server
     * (FEATURE_2610_17 / D10) — of every seller with the <b>given preset</b>: the scheduler's only entry
     * (FEATURE_2609_49 / D1·D2).
     *
     * <p>🔴 Rounds on this path do <b>not</b> stamp failures or partial failures into {@code SyncStatusRecorder}
     * (D13): the screen banner would stay for users who never pressed anything — failures are only logged.
     * Success and per-step anchors are still recorded ({@code lastOrderSyncAt} feeds the banner and the window).
     */
    OrderSyncResult syncAll(OrderSyncPreset preset);

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
         * A round that did not call the marketplace because the same channel is already running
         * (FEATURE_2609_48 / D5). Not a failure but "in progress".
         *
         * <p>🔴 Why {@code syncedAt} is {@code null} (D9): this round read nothing. With {@code now()}
         * {@link #plus} would take it, and when the last account of a full sync is skipped the total time
         * would become a moment nothing was read.
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
