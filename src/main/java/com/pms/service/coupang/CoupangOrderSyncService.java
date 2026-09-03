package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;

import java.util.ArrayList;
import java.util.List;

/**
 * 쿠팡 ordersheets 조회 → order_item 멱등 upsert 동기화.
 *
 * 멱등성 보장: 같은 기간을 여러 번 동기화해도 UNIQUE(account, box, order, item) 키로
 * 신규는 insert, 기존은 가변 필드(status·cancel·hold·count·raw)만 갱신한다 → 중복이 쌓이지 않는다.
 *
 * 커밋 경계는 상태 1개다({@link CoupangOrderStatusSyncer}) — 일부 상태가 실패해도 성공한 상태는 커밋된다.
 */
public interface CoupangOrderSyncService {

    /** 활성 COUPANG 계정 전체 동기화 (결과 합산). */
    SyncResult syncAll();

    /**
     * 계정 1개 동기화 (기본 창 = 오늘(KST) − sync-days).
     * Phase 3 OrderSyncFacade 가 신규/갱신 수를 받기 위해 결과를 반환한다.
     */
    SyncResult syncAccount(MarketplaceAccount account);

    /**
     * 계정 1개를 <b>지정 창</b>으로 동기화 (과거 기간 불러오기, FEATURE_2609_10).
     * 상태 루프·부분 실패 처리는 기본 창과 동일하다.
     */
    SyncResult syncAccount(MarketplaceAccount account, SyncWindow window);

    /**
     * 동기화 결과 집계.
     *
     * @param failedStatuses 조회/적재에 실패한 상태 (부분 성공 표시용). {@link #syncAll} 합산분은
     *                       계정 구분이 없어 <b>로그 용도만</b>이다 — 같은 상태가 여러 계정에서 실패하면
     *                       중복으로 쌓인다(의도적으로 distinct 하지 않는다).
     */
    record SyncResult(int newCount, int updatedCount, int pages, List<CoupangOrderStatus> failedStatuses) {
        public static SyncResult empty() {
            return new SyncResult(0, 0, 0, List.of());
        }

        public SyncResult plus(SyncResult other) {
            List<CoupangOrderStatus> merged = new ArrayList<>(failedStatuses);
            merged.addAll(other.failedStatuses);
            return new SyncResult(
                    newCount + other.newCount,
                    updatedCount + other.updatedCount,
                    pages + other.pages,
                    List.copyOf(merged));
        }
    }
}
