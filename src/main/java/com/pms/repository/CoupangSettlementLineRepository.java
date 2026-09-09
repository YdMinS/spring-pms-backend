package com.pms.repository;

import com.pms.domain.CoupangSettlementLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 쿠팡 정산 라인 미러 리포지토리 (FEATURE_2609_30 / PLAN D6).
 *
 * <p>중립 라인 1건당 미러 1건이므로 조회 축도 그것 하나다 — 미러를 독립적으로 뒤지는 finder 를
 * 늘리지 말 것(집계는 중립 라인이 답한다).
 */
public interface CoupangSettlementLineRepository extends JpaRepository<CoupangSettlementLine, Long> {

    Optional<CoupangSettlementLine> findBySettlementLine_Id(Long settlementLineId);
}
