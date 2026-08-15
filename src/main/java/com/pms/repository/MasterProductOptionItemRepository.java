package com.pms.repository;

import com.pms.domain.MasterProductOptionItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link MasterProductOptionItem} (FEATURE_2608_06 / 3b-1).
 *
 * <p>No {@code @TenantId} — isolation flows through the parent option → master. {@link #findByOptionIdIn}
 * batches items for many options in one query (N+1 guard). Item sets are replaced by
 * {@link #deleteByOptionId} + re-insert.</p>
 */
public interface MasterProductOptionItemRepository extends JpaRepository<MasterProductOptionItem, Long> {

    List<MasterProductOptionItem> findByOptionId(Long optionId);

    List<MasterProductOptionItem> findByOptionIdIn(Collection<Long> optionIds);

    void deleteByOptionId(Long optionId);
}
