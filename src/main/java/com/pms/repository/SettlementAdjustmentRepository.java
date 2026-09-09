package com.pms.repository;

import com.pms.domain.SettlementAdjustment;
import com.pms.domain.SettlementAdjustmentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 지급 묶음 레벨 조정 리포지토리 (FEATURE_2609_30 / PLAN D8).
 *
 * <p>🔴 멱등 키는 {@code (settlement_payout, adjustment_type)} 다 — 재조회 때 지우고 다시 넣지 않는다.
 * delete-insert 는 행 id 를 바꿔 화면·엑셀의 참조를 흔들고, 실패하면 조정이 통째로 사라져 검증식이 깨진다.
 */
public interface SettlementAdjustmentRepository extends JpaRepository<SettlementAdjustment, Long> {

    List<SettlementAdjustment> findBySettlementPayout_Id(Long settlementPayoutId);

    Optional<SettlementAdjustment> findBySettlementPayout_IdAndAdjustmentType(
            Long settlementPayoutId, SettlementAdjustmentType adjustmentType);
}
