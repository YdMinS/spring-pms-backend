package com.pms.repository;

import com.pms.domain.Platform;
import com.pms.domain.PriceChangeLog;
import com.pms.domain.PriceTargetType;
import com.pms.dto.response.PriceChangeView;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Price change history access (FEATURE_2609_28 / PLAN D23).
 *
 * <p>⚠️ ONE nullable-parameter query, not a finder per filter combination: six optional filters
 * would explode into dozens of methods. Same {@code (:param is null or ...)} convention as
 * {@code CustomerInquiryRepository.search}.
 *
 * <p>🔴 The channel columns come from a <b>join</b>
 * ({@code product_listing_option -> product_listing -> master_product}), never from columns on the
 * log row: a cell that is moved to another master must not leave old rows claiming the old master.
 */
@Repository
public interface PriceChangeLogRepository extends JpaRepository<PriceChangeLog, Long> {

    /**
     * History rows, newest first. Every filter is optional; {@code pageable} only caps the size
     * (the ordering lives in the query).
     *
     * @param start inclusive lower bound on the change timestamp, or null
     * @param end   <b>exclusive</b> upper bound, or null — the caller turns a to-date into the next
     *              day so a change made at 23:59 on that date is still included
     */
    @Query("""
            select new com.pms.dto.response.PriceChangeView(
                l.id, l.targetType,
                p.id, p.productName,
                cell.id, cell.name, cell.platform, mp.id,
                o.id, o.optionName,
                l.oldPrice, l.newPrice,
                l.reason, l.purchaseRecordId, l.createdBy, l.createdAt)
            from PriceChangeLog l
              left join l.product p
              left join l.listingOption o
              left join o.productListing cell
              left join cell.masterProduct mp
            where (:productId is null or p.id = :productId)
              and (:optionId is null or o.id = :optionId)
              and (:listingId is null or cell.id = :listingId)
              and (:masterProductId is null or mp.id = :masterProductId)
              and (:platform is null or cell.platform = :platform)
              and (:targetType is null or l.targetType = :targetType)
              and (:start is null or l.createdAt >= :start)
              and (:end is null or l.createdAt < :end)
            order by l.createdAt desc, l.id desc
            """)
    List<PriceChangeView> search(@Param("productId") Long productId,
                                 @Param("optionId") Long optionId,
                                 @Param("listingId") Long listingId,
                                 @Param("masterProductId") Long masterProductId,
                                 @Param("platform") Platform platform,
                                 @Param("targetType") PriceTargetType targetType,
                                 @Param("start") LocalDateTime start,
                                 @Param("end") LocalDateTime end,
                                 Pageable pageable);
}
