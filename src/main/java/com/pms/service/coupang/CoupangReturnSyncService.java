package com.pms.service.coupang;

import com.pms.domain.MarketplaceAccount;

/**
 * 쿠팡 returnRequests(반품/취소 요청 목록) 조회 → order_item 취소수량 보정.
 *
 * ordersheets 만으로는 조회창(최근 N일)을 벗어난 옛 주문의 취소를 못 잡으므로,
 * cancelType=CANCEL 으로 결제완료 단계 취소를 별도 조회해 매칭되는 order_item 의 cancel_count 를 보정한다.
 * (orderId + shipmentBoxId + vendorItemId) 로 order_item 4키 매칭. 매칭 안 되면 무시(예외 없음).
 */
public interface CoupangReturnSyncService {

    /** 계정 1개의 취소 보정. OrderSyncFacade 가 ordersheets 동기화 뒤 호출한다. */
    CancelSyncResult syncCancels(MarketplaceAccount account);

    /**
     * 미완결 클레임의 접수일 범위를 창 단위로 훑어 상태 전이를 따라잡는다(FEATURE_2609_18 D7).
     *
     * 신규 조회 창은 "최근 접수"만 덮으므로, 접수 후 창을 벗어난 건은 UC→CC 로 넘어가도 영영 갱신되지
     * 않는다. 이 메서드가 그 미완결 건을 계속 따라가서 종결시킨다.
     * 먼저 STALE 스윕(D11)으로 종결 신호를 못 받는 건을 걷어내고, 남은 건의 접수일 범위만 조회한다 —
     * 남은 게 없으면 쿠팡을 아예 치지 않는다.
     */
    ClaimTrackingResult trackOpenClaims(MarketplaceAccount account);

    /** 취소 보정 결과 집계. */
    record CancelSyncResult(int matchedUpdated, int pages) {
        public static CancelSyncResult empty() {
            return new CancelSyncResult(0, 0);
        }
    }

    /**
     * 추적 결과 집계.
     *
     * @param slices      실제로 조회한 슬라이스 수 (정상 상태에서는 0 또는 1 — 2 이상이 계속 찍히면 스윕이 안 도는 것)
     * @param staleClosed 이번 회차에 STALE 로 강제 종결한 건수
     */
    record ClaimTrackingResult(int slices, int staleClosed) {
    }
}
