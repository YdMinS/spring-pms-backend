package com.pms.repository;

import com.pms.domain.OrderShipment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 배송 묶음 리포지토리 (FEATURE_2609_26 / PLAN D2). 부모(주문) 경유 finder 만 노출한다. */
public interface OrderShipmentRepository extends JpaRepository<OrderShipment, Long> {

    /** UNIQUE 키(주문 + 배송묶음 식별자)로 조회 — 적재 upsert 의 멱등성 키. */
    Optional<OrderShipment> findByOrder_IdAndExternalShipmentId(Long orderId, String externalShipmentId);
}
