package com.pms.repository;

import com.pms.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 주문 헤더 리포지토리 (FEATURE_2609_26 / PLAN D2).
 *
 * <p>⚠️ 엔티티 {@link Order} 는 {@code jakarta.persistence.criteria.Order}·
 * {@code org.springframework.data.domain.Sort.Order} 와 이름이 겹친다 — 정렬을 쓰는 파일에서 import 주의.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** UNIQUE 키(계정 + 플랫폼 주문번호)로 주문 헤더 조회 — 적재 upsert 의 멱등성 키. */
    Optional<Order> findByMarketplaceAccount_IdAndExternalOrderId(Long accountId, String externalOrderId);
}
