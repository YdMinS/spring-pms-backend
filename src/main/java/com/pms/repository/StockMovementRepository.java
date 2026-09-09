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
     * On-hand per product = SUM of the ledger (D14). Products with no movement at all are absent —
     * they have no ledger history, which is different from "0 on hand".
     */
    @Query("""
            select new com.pms.dto.response.StockBalanceView(p.id, p.productName, coalesce(sum(m.quantity), 0L))
            from StockMovement m join m.product p
            where (:productId is null or p.id = :productId)
              and (:keyword is null or lower(p.productName) like lower(concat('%', :keyword, '%')))
            group by p.id, p.productName
            order by p.productName asc
            """)
    List<StockBalanceView> findBalances(@Param("productId") Long productId,
                                        @Param("keyword") String keyword);

    /** Ledger history for a product / period. product is fetched (open-in-view=false). */
    @EntityGraph(attributePaths = "product")
    @Query("""
            select m from StockMovement m
            where (:productId is null or m.product.id = :productId)
              and m.movedOn >= :from and m.movedOn <= :to
            order by m.movedOn desc, m.id desc
            """)
    List<StockMovement> findHistory(@Param("productId") Long productId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    /**
     * Purchases still waiting to be checked in (Step 6-1).
     *
     * <p>⚠️ {@code left join i.orderLine} is mandatory — a stock-replenishment purchase has no order
     * ({@code ShoppingListItem.orderLine} is nullable) and an inner join would delete the whole
     * category from the list.
     *
     * <p>⚠️ Correction rows ({@code quantity < 0}) are excluded: a reversed purchase is not something
     * that can arrive.
     */
    @Query("""
            select new com.pms.dto.response.PurchaseCandidateView(
                r.id, p.id, p.productName, r.purchasedOn, r.quantity,
                (select coalesce(sum(m.quantity), 0L) from StockMovement m
                   where m.purchaseRecord.id = r.id
                     and m.movementType = com.pms.domain.StockMovementType.STOCK_IN),
                r.unitPrice, o.externalOrderId)
            from PurchaseRecord r
              join r.item i
              join i.product p
              left join i.orderLine ol
              left join ol.order o
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
