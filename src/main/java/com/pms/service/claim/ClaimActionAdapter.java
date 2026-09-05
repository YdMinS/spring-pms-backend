package com.pms.service.claim;

import com.pms.domain.ClaimAction;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.OrderClaim;

import java.util.List;
import java.util.Set;

/**
 * 플랫폼별 클레임 처리 액션 실행 seam (FEATURE_2609_21 / PLAN D14·D17).
 *
 * <p>{@code ClaimSyncAdapter}(2609_18 D21)와 같은 관례다 — 서비스는 {@code List<ClaimActionAdapter>} 를
 * 주입받아 {@link #platform()} 으로 고른다. 어댑터가 없는 플랫폼(네이버)은 {@code availableActions} 가
 * <b>빈 목록</b>이고(예외 아님 — UI 가 패널을 안 그린다) {@code execute} 는 400 이다.
 *
 * <p>🔴 <b>서비스·컨트롤러·응답 DTO 어디에도 {@code "COUPANG"} 문자열이 없어야 한다.</b> 플랫폼을 아는
 * 것은 {@code OrderClaim.platform} 과 어댑터 구현뿐 — 그래야 네이버가 클래스 1개 추가로 붙는다(PLAN §8).
 *
 * <p>조회는 서비스의 일이고 어댑터는 <b>받은 것으로 판정·조립만</b> 한다. 그래서 {@code execute} 가
 * {@code siblings} 를, {@code availableActions} 가 {@code alreadySucceeded} 를 인자로 받는다 —
 * 어댑터가 다시 조회하면 "누가 형제를 아는가"가 두 곳이 된다.
 */
public interface ClaimActionAdapter {

    /** {@code order_claim.platform} 과 대조할 값. 예: "COUPANG". */
    String platform();

    /**
     * 지금 가능한 액션. 플랫폼 상태 코드를 아는 유일한 자리다(D2).
     *
     * <p>판정은 정규화 {@code status} 가 아니라 <b>{@code platform_status} 원문 화이트리스트</b>로 한다 —
     * 정규화는 화면 필터용 축약이라 서로 다른 원문이 한 칸에 뭉친다. 모르는 값·null 은 액션 없음(D3).
     *
     * @param alreadySucceeded 같은 접수(형제 라인 포함)에서 이미 성공한 액션 — 여기 든 액션은 내리지 않는다
     */
    List<ClaimActionOption> availableActions(OrderClaim claim, Set<ClaimAction> alreadySucceeded);

    /**
     * 실제 전송. 로컬 상태는 바꾸지 않는다(D7 — 다음 동기화가 갱신한다).
     *
     * @param siblings 같은 접수의 라인 전부. 접수 단위 파라미터(반품 승인의 {@code cancelCount})는 이 합계다
     * @throws UnsupportedOperationException 이 어댑터가 아직 구현하지 않은 액션
     */
    ClaimActionOutcome execute(MarketplaceAccount account,
                               List<OrderClaim> siblings,
                               ClaimActionCommand command);
}
