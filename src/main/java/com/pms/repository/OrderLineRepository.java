package com.pms.repository;

import com.pms.domain.OrderLine;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 주문 라인 리포지토리 (FEATURE_2609_26 / PLAN D2). */
public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {

    /**
     * 금액 백필 배치 — 금액이 비어 있는 라인을 id 오름차순 keyset 페이징으로 읽는다.
     *
     * <p>⚠️ 항상 첫 페이지를 다시 읽는 방식은 쓰지 않는다: 파싱에 실패해 계속 null 로 남는 행이
     * 매 회차 다시 뽑혀 무한 루프가 된다. {@code lastId} 로 전진하면 각 행을 정확히 한 번만 본다.
     */
    @Query("SELECT l FROM OrderLine l WHERE l.unitPrice IS NULL AND l.id > :lastId ORDER BY l.id ASC")
    List<OrderLine> findAmountBackfillBatch(@Param("lastId") Long lastId, Pageable pageable);

    /**
     * 전 테넌트 id 목록 — {@code @TenantId} 필터를 우회하는 native 쿼리.
     *
     * <p>비-웹 컨텍스트(마이그레이션 러너)는 TenantContext 가 비어 있어 일반 조회가 NO_TENANT 로
     * 0건이 된다. 러너는 이 목록을 돌며 테넌트를 명시 set 한다(backend CLAUDE.md §9).
     */
    @Query(value = "SELECT DISTINCT tenant_id FROM order_line", nativeQuery = true)
    List<Long> findDistinctTenantIds();
}
