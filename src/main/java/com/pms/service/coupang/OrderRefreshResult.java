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
 * @param cancelled       쿠팡이 "이미 취소 또는 반품된 주문"이라고 답한 주문 (2026-09-16 신설)
 * @param failed          조회·파싱 실패 주문 (사유 원문 포함)
 * @param unsupported     비-COUPANG 계정이라 조회할 수 없는 주문번호
 */
public record OrderRefreshResult(
        int requestedOrders,
        int refreshed,
        List<String> empty,
        List<CancelledOrder> cancelled,
        List<FailedOrder> failed,
        List<String> unsupported) {

    /** 실패 1건 — 사유는 사용자에게 그대로 보여준다(고칠 수 있는 정보가 여기 담긴다). */
    public record FailedOrder(String externalOrderId, String reason) {}

    /**
     * 쿠팡에서 이미 사라진 주문 1건 (2026-09-16).
     *
     * <p>쿠팡 단건 조회는 이런 주문에 <b>400 + "해당 주문이 취소 또는 반품 되었습니다"</b> 로 답한다 —
     * 실패가 아니라 "더 이상 진행 중이 아니다"라는 확정적인 답이라 따로 분류한다.
     *
     * <p>🔴 메시지가 <b>취소와 반품을 구분해 주지 않으므로</b> 로컬 상태로 가른다 — 발송 전 라인만
     * 전량취소로 확정하고({@code cancelledLines}), 발송 이후 라인은 손대지 않는다({@code keptLines}).
     * 반품은 배송까지 끝나 매출로 잡힌 건이라 취소로 지우면 있었던 매출이 사라진다.
     *
     * @param externalOrderId 주문번호
     * @param cancelledLines  발송 전이라 전량취소로 확정한 라인 수
     * @param keptLines       이미 발송 이후라 금액·수량을 그대로 둔 라인 수
     */
    public record CancelledOrder(String externalOrderId, int cancelledLines, int keptLines) {}
}
