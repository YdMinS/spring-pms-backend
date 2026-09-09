package com.pms.repository;

import com.pms.domain.StockMovement;
import com.pms.dto.response.PurchaseCandidateView;
import com.pms.dto.response.ReturnCandidateView;
import com.pms.dto.response.StockBalanceView;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Physical stock ledger access (FEATURE_2609_28 / PLAN D14).
 *
 * <p>⚠️ The on-hand balance is <b>aggregated in SQL</b>, never by loading every movement and summing
 * in Java — a ledger only grows.
 *
 * <p>⚠️ Nullable filters follow this project's {@code (:param is null or ...)} convention
 * (see {@link StockLogRepository}); the caller normalises blank strings to null so {@code like '%%'}
 * cannot silently disable the filter.
 */
@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /**
     * On-hand per (product × seller) = SUM of the ledger
     * (D14, revised by PLAN 2609_29 D5). Products with no movement at all are absent —
     * they have no ledger history, which is different from "0 on hand".
     *
     * <p>🔴 The seller is part of the GROUP BY, not a decoration: stock is not shared (2609_29 D4),
     * so one product bought by two sellers must produce two rows. Collapsing them would report a
     * balance nobody owns.
     */
    @Query("""
            select new com.pms.dto.response.StockBalanceView(
                p.id, p.productName, s.id, s.sellerName, coalesce(sum(m.quantity), 0L))
            from StockMovement m join m.product p join m.seller s
            where (:productId is null or p.id = :productId)
              and (:sellerId is null or s.id = :sellerId)
              and (:keyword is null or lower(p.productName) like lower(concat('%', :keyword, '%')))
            group by p.id, p.productName, s.id, s.sellerName
            order by p.productName asc, s.sellerName asc
            """)
    List<StockBalanceView> findBalances(@Param("productId") Long productId,
                                        @Param("sellerId") Long sellerId,
                                        @Param("keyword") String keyword);

    /** Ledger history for a (product, seller) / period. product + seller are fetched (open-in-view=false). */
    @EntityGraph(attributePaths = {"product", "seller"})
    @Query("""
            select m from StockMovement m
            where (:productId is null or m.product.id = :productId)
              and (:sellerId is null or m.seller.id = :sellerId)
              and m.movedOn >= :from and m.movedOn <= :to
            order by m.movedOn desc, m.id desc
            """)
    List<StockMovement> findHistory(@Param("productId") Long productId,
                                    @Param("sellerId") Long sellerId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    /**
     * Purchases still waiting to be checked in (Step 6-1), rebuilt on the purchase ledger itself
     * (PLAN 2609_29 D20).
     *
     * <p>🔴 The old shape reached the product and the order through the shopping list item. D3
     * removed that FK, so those joins do not merely return wrong rows — they break context startup.
     * The record now carries its own product and seller, so no order join exists at all and
     * {@code externalOrderId} is gone with it.
     *
     * <p>⚠️ Correction rows ({@code quantity < 0}) are excluded: a reversed purchase is not something
     * that can arrive.
     *
     * <p>⚠️ While D19 keeps {@code recordStock} on, every purchase is checked in at once and this
     * query legitimately returns nothing. Empty is not broken.
     */
    @Query("""
            select new com.pms.dto.response.PurchaseCandidateView(
                r.id, p.id, p.productName, s.sellerName, r.purchasedOn, r.quantity,
                (select coalesce(sum(m.quantity), 0L) from StockMovement m
                   where m.purchaseRecord.id = r.id
                     and m.movementType = com.pms.domain.StockMovementType.STOCK_IN),
                r.unitPrice)
            from PurchaseRecord r
              join r.product p
              join r.seller s
            where r.quantity > 0
              and (:productId is null or p.id = :productId)
              and r.quantity > (select coalesce(sum(m2.quantity), 0L) from StockMovement m2
                                  where m2.purchaseRecord.id = r.id
                                    and m2.movementType = com.pms.domain.StockMovementType.STOCK_IN)
            order by r.purchasedOn asc, r.id asc
            """)
    List<PurchaseCandidateView> findPurchaseCandidates(@Param("productId") Long productId);

    /**
     * Return claims still waiting to be checked back in (Step 6-2).
     *
     * <p>🔴 Deliberately NOT filtered by {@code collectStatus} — see {@link ReturnCandidateView}.
     *
     * <p>⚠️ {@code left join c.orderLine}: an unmatched claim (order matching failed) must stay in
     * the list, otherwise physically returned goods cannot be checked in.
     */
    @Query("""
            select new com.pms.dto.response.ReturnCandidateView(
                c.id, ol.id, c.itemName, c.externalOrderId, c.quantity,
                (select coalesce(sum(m.quantity), 0L) from StockMovement m
                   where m.orderClaim.id = c.id
                     and m.movementType = com.pms.domain.StockMovementType.RETURN_IN),
                c.status, c.collectStatus, c.receivedAt)
            from OrderClaim c
              left join c.orderLine ol
            where c.claimType = com.pms.domain.ClaimType.RETURN
              and c.quantity > (select coalesce(sum(m2.quantity), 0L) from StockMovement m2
                                  where m2.orderClaim.id = c.id
                                    and m2.movementType = com.pms.domain.StockMovementType.RETURN_IN)
            order by c.receivedAt desc, c.id desc
            """)
    List<ReturnCandidateView> findReturnCandidates();
}
