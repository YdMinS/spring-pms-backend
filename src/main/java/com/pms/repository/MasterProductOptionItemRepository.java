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
     * Same batch, with {@code product} and {@code option} fetched (FEATURE_2609_28 / PLAN D13).
     *
     * <p>BOM expansion reads the component's NAME for every item, and the parent option's id to group
     * the rows back per option. With {@code open-in-view=false} and a plain LAZY item that is one extra
     * query per component — the join fetch keeps a whole outbound screen at a single statement.
     */
    @Query("select i from MasterProductOptionItem i "
            + "join fetch i.product join fetch i.option where i.option.id in :optionIds")
    List<MasterProductOptionItem> findWithProductByOptionIdIn(@Param("optionIds") Collection<Long> optionIds);

    /**
     * 이 물품들을 쓰는 옵션 + 그 마스터 (FEATURE_2609_28 / PLAN D4 ②).
     *
     * <p>원가 파급 미리보기는 매입이 들어온 물품에서 출발해 마스터까지 거슬러 올라간다. 마스터 이름을
     * 응답에 실어야 하는데 {@code option.masterProduct} 는 LAZY 라 행마다 깨우면 N+1 이 된다 —
     * {@code join fetch} 로 한 번에 받는다.
     *
     * <p>⚠️ 테넌트 격리는 부모(마스터)의 {@code @TenantId} 를 타고 흐른다. 이 쿼리에 수동 테넌트 조건을
     * 넣지 말 것 — 출발점인 물품 목록 자체가 이미 테넌트 필터된 매입 이력에서 나온다.
     */
    @Query("select i from MasterProductOptionItem i "
            + "join fetch i.option o join fetch o.masterProduct "
            + "where i.product.id in :productIds")
    List<MasterProductOptionItem> findWithMasterByProductIdIn(@Param("productIds") Collection<Long> productIds);

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
