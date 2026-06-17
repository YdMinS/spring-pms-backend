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

    /** 취소 보정 결과 집계. */
    record CancelSyncResult(int matchedUpdated, int pages) {
        public static CancelSyncResult empty() {
            return new CancelSyncResult(0, 0);
        }
    }
}
