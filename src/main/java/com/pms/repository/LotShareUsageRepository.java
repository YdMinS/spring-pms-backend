package com.pms.repository;

import com.pms.domain.LotShareUsage;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Split of an undecided intake row's outgoing rows per owner (FEATURE_2610_19 D131). */
@Repository
public interface LotShareUsageRepository extends JpaRepository<LotShareUsage, Long> {

    /** D131: a re-split deletes that intake row's rows first. Bulk delete; no automatic clear — the callers keep using their loaded entities in the same transaction. */
    @Modifying(flushAutomatically = true)
    @Query("delete from LotShareUsage u where u.inMovement.id = :inMovementId")
    int deleteByInMovementId(@Param("inMovementId") Long inMovementId);

    @EntityGraph(attributePaths = {"shareRecord", "shareRecord.seller"})
    List<LotShareUsage> findByInMovementIdIn(Collection<Long> inMovementIds);

    @EntityGraph(attributePaths = {"shareRecord", "shareRecord.seller"})
    List<LotShareUsage> findByOutMovementIdIn(Collection<Long> outMovementIds);
}
