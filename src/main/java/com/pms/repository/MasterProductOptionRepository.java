package com.pms.repository;

import com.pms.domain.MasterProductOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link MasterProductOption} (FEATURE_2608_06 / 3b-1).
 *
 * <p>No {@code @TenantId} — isolation flows through the parent master, so finders are master-scoped, or take option
 * ids reached from tenant-scoped rows ({@link #findWithMasterProductByIdIn} — the order detail, FEATURE_2611_15). The
 * inherited PK {@code findById} is used only after the parent master is tenant-scoped.</p>
 */
public interface MasterProductOptionRepository extends JpaRepository<MasterProductOption, Long> {

    List<MasterProductOption> findByMasterProductId(Long masterProductId);

    /**
     * Options of several masters in one query (N+1 guard) — used by the by-components lookup to report
     * each duplicate's option count. Callers must have tenant-scoped {@code masterProductIds} first.
     */
    List<MasterProductOption> findByMasterProductIdIn(Collection<Long> masterProductIds);

    /**
     * Options with their master in one statement (FEATURE_2611_15 / D1 · D3 — the order detail names the master
     * option and the master of every line). Callers pass option ids reached from tenant-scoped order lines.
     */
    @Query("select o from MasterProductOption o join fetch o.masterProduct where o.id in :ids")
    List<MasterProductOption> findWithMasterProductByIdIn(@Param("ids") Collection<Long> ids);
}
