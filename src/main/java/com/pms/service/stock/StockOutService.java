package com.pms.service.stock;

import com.pms.domain.OrderStatus;
import com.pms.dto.request.OutboundConfirmRequest;
import com.pms.dto.response.OutboundResponse;
import com.pms.dto.response.StockMovementView;

import java.util.List;

/**
 * 출고 확인 — 주문에서 출발하는 {@code STOCK_OUT} (FEATURE_2609_28 / PLAN D11·D12·D18).
 *
 * <p>입고·폐기·조정은 {@link StockLedgerService} 가, 출고는 여기가 맡는다. 나뉜 이유는 권한이 아니라
 * <b>출발점</b>이다 — 입고는 물품에서, 출고는 주문에서 출발한다. {@code order_line_id} 가 채워져야
 * 나중에 원가 스냅샷이 "어느 주문으로 나갔는지"를 답할 수 있다.
 *
 * <p>🔴 <b>사람의 확인 없이 여기를 부르지 않는다</b>(D18). 발송처리·송장 업로드·주문 동기화 어디에서도
 * {@link #confirm} 을 호출하면 안 된다 — 그 순간 원장이 창고가 아니라 플랫폼 상태를 베끼기 시작하고,
 * 실물을 세는 기능으로서의 가치가 사라진다.
 */
public interface StockOutService {

    /**
     * 아직 안 나간 주문 라인 + 각 라인이 소진할 물품·수량.
     *
     * @param sellerId null = 전 판매자
     * @param status   null = {@code PAID} + {@code PREPARING} 둘 다. 종결 상태는 받지 않는다
     */
    OutboundResponse outbound(Long sellerId, OrderStatus status);

    /** 확인한 만큼 {@code STOCK_OUT} 을 남긴다. 라인마다 1행(D12). 반환 = 생성된 원장 행. */
    List<StockMovementView> confirm(OutboundConfirmRequest request);
}
