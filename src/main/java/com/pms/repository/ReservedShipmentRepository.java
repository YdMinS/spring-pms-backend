package com.pms.repository;

import com.pms.domain.ReservedShipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 예약 발송 리포지토리 (FEATURE_2609_75). {@code @TenantId} 자동 필터 — 수동 테넌트 조건 금지.
 * PK {@code findById()} 는 테넌트 필터가 안 걸리므로 화면 경로의 단건 조회는 {@link #findScopedById} 를 쓴다.
 */
public interface ReservedShipmentRepository extends JpaRepository<ReservedShipment, Long> {

    /** 테넌트 범위 단건 조회. 다른 테넌트 id 면 empty. */
    @Query("select r from ReservedShipment r where r.id = :id")
    Optional<ReservedShipment> findScopedById(@Param("id") Long id);
}
