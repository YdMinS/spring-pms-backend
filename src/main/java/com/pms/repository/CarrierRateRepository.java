package com.pms.repository;

import com.pms.domain.CarrierRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CarrierRateRepository extends JpaRepository<CarrierRate, Long> {
    Optional<CarrierRate> findByIsDefaultTrue();

    /** True if any rate references the given carrier (FK guard for carrier deletion). */
    boolean existsByCarrierId(Long carrierId);

    /**
     * 모든 요율 + 택배사 — 절약 집계가 택배비 1건분을 고르는 재료(FEATURE_2609_41 / PLAN 2609_41 S15).
     *
     * <p>🔴 {@link #findByIsDefaultTrue()} 를 쓰면 안 된다: {@code is_default} 는 택배사별이 아니라
     * <b>시스템 전체에 1건</b>이라({@code CarrierRateServiceImpl} 이 새 기본을 세울 때 기존 기본을 해제한다)
     * 그것으로 고르면 기본을 가진 택배사 하나를 뺀 나머지 박스의 택배 절약이 전부 NULL 이 된다.
     * 고르는 규칙은 <b>박스의 포장일 기준 최신 요율</b>이고, 그 판정은 집계 서비스가 소유한다.
     *
     * <p>⚠️ 요율표는 택배사 × 몇 건 규모라 통째로 읽는다 — 박스마다 조회하면 그대로 N+1 이다.
     */
    @Query("select r from CarrierRate r join fetch r.carrier")
    List<CarrierRate> findAllWithCarrier();
}
