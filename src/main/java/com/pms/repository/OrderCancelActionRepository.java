package com.pms.repository;

import com.pms.domain.OrderCancelAction;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * order_cancel_action 접근 (FEATURE_2609_25).
 *
 * <p>적재 전용이다 — 이력 <b>조회 화면을 만들지 않는다</b>(PLAN D17). 그래서 finder 를 두지 않는다.
 * 중복 취소 가드도 없다(D: 동시성) — 접수 단위 가드가 성립하지 않아 버튼 비활성 + 확인 다이얼로그가 방어다.
 * ⚠️ {@code @TenantId} 가 SELECT 를 자동 필터하므로 쿼리에 tenant 조건을 직접 넣지 않는다.
 */
public interface OrderCancelActionRepository extends JpaRepository<OrderCancelAction, Long> {
}
