package com.pms.repository;

import com.pms.domain.ClaimAction;
import com.pms.domain.OrderClaimAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * order_claim_action 접근 (FEATURE_2609_21).
 *
 * <p>조회는 둘 다 <b>성공 기록만</b> 본다 — 실패 기록은 재시도를 막지 않는다(D6).
 * ⚠️ {@code @TenantId} 가 SELECT 를 자동 필터하므로 쿼리에 tenant 조건을 직접 넣지 않는다.
 */
public interface OrderClaimActionRepository extends JpaRepository<OrderClaimAction, Long> {

    /**
     * 단건 가드(D6) — 넘긴 claim id 들(= 같은 접수의 형제 라인 전체) 중 하나라도 그 액션을 성공했는가.
     *
     * ⚠️ 클릭한 claim 하나가 아니라 <b>형제 라인 전체</b>를 넘겨야 한다. 쿠팡 액션의 식별자는
     * {@code receiptId}(접수)인데 우리 {@code order_claim} 은 라인 단위라, 어느 행에서 눌러도 접수
     * 전체에 적용된다.
     */
    boolean existsByOrderClaim_IdInAndActionAndSucceededTrue(
            List<Long> orderClaimIds, ClaimAction action);

    /**
     * 목록용 벌크 조회(Step 8) — 넘긴 claim id 들의 성공 기록 전부.
     *
     * <p>{@code getClaims} 는 페이지가 아니라 기간 전체 List 라, claim 마다 조회하면 N+1 이 그대로
     * 조회 수가 된다. 목록 크기와 무관하게 이 쿼리 1개로 끝낸다.
     * ⚠️ 반환 엔티티의 {@code orderClaim} 은 LAZY 프록시다 — {@code getId()} 만 읽을 것.
     */
    List<OrderClaimAction> findByOrderClaim_IdInAndSucceededTrue(List<Long> orderClaimIds);
}
