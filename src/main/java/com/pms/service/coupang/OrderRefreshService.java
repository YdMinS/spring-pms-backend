package com.pms.service.coupang;

import com.pms.dto.request.OrderRefreshRequest;

/**
 * 주문 최신화 레그: 선택한 주문 라인 → 주문번호 dedupe → 쿠팡 단건 발주서 조회 → 로컬 3층 적재.
 *
 * <p>정기·야간 동기화의 조회 창(주문일 기준)을 벗어나 고착된 주문을 손으로 푸는 수단이다
 * (FEATURE_2609_50). 쿠팡 단건 조회는 상태·날짜 조건이 없어 며칠 지난 주문도 잡힌다.
 *
 * <p>일괄(목록 체크)과 개별(주문 상세 버튼)이 <b>같은 메서드</b>를 쓴다.
 *
 * ⚠️ 조회 전용이다 — 마켓에 아무것도 쓰지 않는다. 저장은 {@link OrderUpserter} 가 전담한다.
 * ⚠️ {@code AccountSyncLock} 을 잡지 않는다(D4) — 주문당 1회이고 upsert 가 멱등이라
 *    전량 동기화가 도는 중에도 사용자 버튼은 성공해야 한다.
 * ⚠️ 창({@code sync-days}·{@code terminal-sync-min-days}·{@code SyncWindow})을 건드리지 않는다.
 */
public interface OrderRefreshService {

    /**
     * 선택한 라인들의 주문번호를 dedupe 해 주문마다 쿠팡 단건 조회 1회를 돌고 결과를 집계한다.
     *
     * @param request 사용자가 체크한 order_line id 목록
     * @return 주문번호 단위 4분류 집계(refreshed / empty / failed / unsupported)
     * @throws IllegalArgumentException 유효한 라인이 하나도 없거나 주문 수가 상한을 넘을 때(→ 400)
     */
    OrderRefreshResult refresh(OrderRefreshRequest request);
}
