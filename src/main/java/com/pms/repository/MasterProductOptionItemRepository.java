package com.pms.repository;

import com.pms.domain.MasterProductOptionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Drop every item row of one option (96 / ⑦). ⚠️ <b>Bulk JPQL on purpose</b> — the derived
     * {@code deleteByOptionId} loaded the rows and queued {@code em.remove}, and Hibernate's ActionQueue runs
     * INSERTs before DELETEs on flush, so the "replace = delete + re-insert" contract above blew up on
     * {@code uq_mpoi_option_product} whenever the new item set reused a (option, product) pair — i.e. every
     * option edit that kept its components. The bulk statement executes immediately, before the re-insert.
     *
     * <p>🔴 Do <b>not</b> add {@code clearAutomatically = true}: callers keep using the {@code option}
     * instance after this call (new items' parent reference, and the LAZY {@code delivery}/{@code package_}
     * associations read while rebuilding the row). Clearing the context would detach it and, with
     * {@code open-in-view=false}, turn every plain option edit into a {@code LazyInitializationException}.
     * {@code flushAutomatically} alone is safe — the stale item instances left in the first-level cache are
     * not dirty, so nothing re-inserts or updates them.</p>
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from MasterProductOptionItem i where i.option.id = :optionId")
    void deleteByOptionId(@Param("optionId") Long optionId);
}
