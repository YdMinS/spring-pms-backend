package com.pms.repository;

import com.pms.domain.MasterProductOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link MasterProductOption} (FEATURE_2608_06 / 3b-1).
 *
 * <p>No {@code @TenantId} — isolation flows through the parent master, so only master-scoped finders are
 * exposed. The inherited PK {@code findById} is used only after the parent master is tenant-scoped.</p>
 */
public interface MasterProductOptionRepository extends JpaRepository<MasterProductOption, Long> {

    List<MasterProductOption> findByMasterProductId(Long masterProductId);

    /**
     * Options of several masters in one query (N+1 guard) — used by the by-components lookup to report
     * each duplicate's option count. Callers must have tenant-scoped {@code masterProductIds} first.
     */
    List<MasterProductOption> findByMasterProductIdIn(Collection<Long> masterProductIds);
}
