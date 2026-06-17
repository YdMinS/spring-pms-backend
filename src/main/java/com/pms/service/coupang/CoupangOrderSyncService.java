package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;

/**
 * 쿠팡 ordersheets 조회 → order_item 멱등 upsert 동기화.
 *
 * 멱등성 보장: 같은 기간을 여러 번 동기화해도 UNIQUE(account, box, order, item) 키로
 * 신규는 insert, 기존은 가변 필드(status·cancel·hold·count·raw)만 갱신한다 → 중복이 쌓이지 않는다.
 *
 * Phase 2 범위: ordersheets 신규/갱신 upsert 까지. 취소 보정(returnRequests)·조회/트리거 API 는
 * Phase 3(OrderSyncFacade)에서 추가한다.
 */
public interface CoupangOrderSyncService {

    /** 활성 COUPANG 계정 전체 동기화 (결과 합산). */
    SyncResult syncAll();

    /** 계정 1개 동기화. Phase 3 OrderSyncFacade 가 신규/갱신 수를 받기 위해 결과를 반환한다. */
    SyncResult syncAccount(MarketplaceAccount account);

    /** 동기화 결과 집계. */
    record SyncResult(int newCount, int updatedCount, int pages) {
        public static SyncResult empty() {
            return new SyncResult(0, 0, 0);
        }

        public SyncResult plus(SyncResult other) {
            return new SyncResult(
                    newCount + other.newCount,
                    updatedCount + other.updatedCount,
                    pages + other.pages);
        }
    }
}
