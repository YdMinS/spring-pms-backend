package com.pms.service.coupang;

import java.util.List;

/**
 * 주문 최신화 결과 집계 (FEATURE_2609_50 / D6).
 *
 * <p>모든 목록이 <b>주문번호 단위</b>다 — 사용자가 체크한 건 라인이지만, 조회·보고 단위는 주문이다(D1).
 *
 * @param requestedOrders dedupe 후 실제 조회를 시도한 주문 수
 * @param refreshed       박스를 1개 이상 반영한 주문 수
 * @param empty           쿠팡이 0박스를 돌려준 주문번호 — 전량 취소로 추정(D7, 실패 아님)
 * @param failed          조회·파싱 실패 주문 (사유 원문 포함)
 * @param unsupported     비-COUPANG 계정이라 조회할 수 없는 주문번호
 */
public record OrderRefreshResult(
        int requestedOrders,
        int refreshed,
        List<String> empty,
        List<FailedOrder> failed,
        List<String> unsupported) {

    /** 실패 1건 — 사유는 사용자에게 그대로 보여준다(고칠 수 있는 정보가 여기 담긴다). */
    public record FailedOrder(String externalOrderId, String reason) {}
}
