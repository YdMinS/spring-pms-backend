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

    /** 계정 1개 동기화. 없는 계정이면 ResourceNotFoundException. */
    OrderSyncResult sync(Long accountId);

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

    /** 동기화 결과 집계 (신규/갱신 주문 수 + 취소 보정 수). */
    record OrderSyncResult(LocalDateTime syncedAt, int newOrders, int updatedOrders, int canceledUpdated) {
        public static OrderSyncResult empty() {
            return new OrderSyncResult(LocalDateTime.now(), 0, 0, 0);
        }

        public OrderSyncResult plus(OrderSyncResult other) {
            return new OrderSyncResult(
                    other.syncedAt,
                    newOrders + other.newOrders,
                    updatedOrders + other.updatedOrders,
                    canceledUpdated + other.canceledUpdated);
        }
    }
}
