package com.pms.service.claim;

import com.pms.domain.MarketplaceAccount;

/**
 * 주문 라인이 붙지 않은 클레임을 <b>쿠팡 단건 발주서 조회 → 적재 → 재매칭</b> 으로 연결한다 (FEATURE_2609_18 / 04).
 *
 * <p>반품이 유실되지 않게 하는 것은 01(적재)이 이미 했다 — 여기서 하는 일은 <b>화면에 주문 정보가 붙게</b>
 * 만드는 것이다. 미연결이어도 클레임 자체는 이미 저장돼 있다(D12).</p>
 *
 * <p>호출 수는 미연결 클레임 수가 아니라 <b>미연결 orderId 수</b>에 비례한다 — 한 주문에서 3라인이
 * 반품되면 조회는 1회다. 회차당 상한({@code coupang.claim-backfill-max-orders})을 넘는 분은 다음 회차가
 * 가져간다(연결된 건은 대상에서 빠지므로 회차마다 줄어든다).</p>
 *
 * <p>⚠️ 진입점은 주문 동기화 하나다({@code OrderSyncFacadeImpl.syncOne}). 테넌트 필터가
 * {@code TenantContext} 에 의존하므로 스케줄러·컨트롤러에서 직접 부르지 말 것.</p>
 */
public interface ClaimOrderBackfillService {

    /** 한 계정의 미연결 클레임을 백필한다. 반환 = (조회한 orderId 수, 새로 연결된 claim 수). */
    BackfillResult backfill(MarketplaceAccount account);

    record BackfillResult(int ordersFetched, int claimsLinked) {}
}
