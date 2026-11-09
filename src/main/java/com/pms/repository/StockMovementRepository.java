package com.pms.repository;

import com.pms.domain.Product;
import com.pms.domain.StockMovement;
import com.pms.domain.StockMovementType;
import com.pms.dto.response.PurchaseCandidateView;
import com.pms.dto.response.ReturnCandidateView;
import com.pms.dto.response.StockBalanceView;
import com.pms.dto.response.StockOutSumView;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Physical stock ledger access (FEATURE_2609_28 / PLAN D14).
 *
 * <p>⚠️ The on-hand balance is <b>aggregated in SQL</b>, never by loading every movement and summing
 * in Java — a ledger only grows.
 *
 * <p>⚠️ Nullable filters follow this project's {@code (:param is null or ...)} convention. The caller
 * normalises blank strings to null so {@code like '%%'} cannot silently disable the filter.
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
     *
     * <p>{@code left join m.seller}: rows with no seller (FEATURE_2610_19 D105 · D124) form their own
     * 「판매자 미정」 row (seller null) instead of silently disappearing (D116 ②). The assigned shares of those
     * rows are moved to their owners by {@code StockLedgerServiceImpl.balances} (D131).
     *
     * <p>{@code p.imageUrl} rides along for the row photo (FEATURE_2611_02 D8) — one value per product, so it does not
     * split a group.
     */
    @Query("""
            select new com.pms.dto.response.StockBalanceView(
                p.id, p.productName, p.imageUrl, s.id, s.sellerName, coalesce(sum(m.quantity), 0L))
            from StockMovement m join m.product p left join m.seller s
            where (:productId is null or p.id = :productId)
              and (:sellerId is null or s.id = :sellerId)
              and (:keyword is null or lower(p.productName) like lower(concat('%', :keyword, '%')))
            group by p.id, p.productName, p.imageUrl, s.id, s.sellerName
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
     * Already-confirmed outbound quantity per (order line × product) (FEATURE_2609_28 / PLAN D12).
     *
     * <p>🔴 This sum is the ONE source of truth for "how much of this line already left"
     * ({@code STOCK_OUT} rows). It is deliberately NOT derived from {@code order_line.cancel_qty}:
     * the return sync raises that column ({@code CoupangReturnSyncServiceImpl:369}), so a cancel-based
     * calculation would silently restore stock nobody put back on a shelf.
     *
     * <p>⚠️ The quantity comes back NEGATIVE, exactly as stored. The service flips it for the screen.
     */
    @Query("""
            select new com.pms.dto.response.StockOutSumView(
                m.orderLine.id, m.product.id, coalesce(sum(m.quantity), 0L))
            from StockMovement m
            where m.movementType = com.pms.domain.StockMovementType.STOCK_OUT
              and m.orderLine.id in :orderLineIds
            group by m.orderLine.id, m.product.id
            """)
    List<StockOutSumView> findStockOutSums(@Param("orderLineIds") Collection<Long> orderLineIds);

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
     * <p>⚠️ Purchases saved while the stock link setting was off ({@code stockLinked = false}) never wait here,
     * even after the setting is turned on (FEATURE_2610_09 D71 ④ · D84). While the setting is on, every purchase is
     * checked in at once and this query legitimately returns nothing. Empty is not broken.
     *
     * <p>⚠️ A receipt assignment ({@code sourceMovement} set, FEATURE_2610_19 D130) is already in stock and never
     * waits here. An undecided purchase (seller null) is left out by {@code join r.seller}.
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
              and r.stockLinked = true
              and r.sourceMovement is null
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

    /**
     * Row count for one product, shown as informational history in the product usage screen
     * (FEATURE_2609_69 / A). History never blocks deletion — it is displayed only.
     */
    long countByProductId(Long productId);

    /**
     * Move every row of this product onto another product (FEATURE_2609_69 / B, merge).
     *
     * <p>🔴 {@code clearAutomatically}/{@code flushAutomatically} are load-bearing, not decoration: a bulk
     * UPDATE bypasses the persistence context, so without them the delete guard later in the same merge
     * transaction would read a stale first-level cache, wrongly report "still linked" and roll the whole
     * merge back.</p>
     *
     * @return number of rows moved
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE StockMovement s SET s.product = :target WHERE s.product.id = :source")
    int reassignProduct(@Param("target") Product target, @Param("source") Long source);

    /**
     * Rows tied to the given purchase records (FEATURE_2610_18 D101) — the stock rows of a receipt's lines, moved to
     * the new payer seller when the receipt header changes its payer and to a new purchase day when it changes its day
     * (FEATURE_2611_21 D26), and the stock rows of a purchase record moved by 「판매자 바꾸기」 (FEATURE_2611_01 D7).
     */
    List<StockMovement> findByPurchaseRecordIdIn(Collection<Long> purchaseRecordIds);

    /**
     * Every row of one product in record order (FEATURE_2610_19 D107) — the input of every lot calculation
     * ({@code LotLedgerServiceImpl}). The order seller path is fetched because the borrower of an outbound row is
     * always the order's seller (D33).
     */
    @EntityGraph(attributePaths = {"seller", "sourceMovement", "purchaseRecord", "orderClaim",
            "orderLine", "orderLine.order", "orderLine.order.marketplaceAccount",
            "orderLine.order.marketplaceAccount.seller"})
    List<StockMovement> findByProductIdOrderByIdAsc(Long productId);

    /** FEATURE_2610_19 D150 (b): the product has an undecided intake row ({@code STOCK_IN}, seller null). */
    boolean existsByProductIdAndSellerIsNullAndMovementType(Long productId, StockMovementType movementType);

    /** The rows 「판매자 바꾸기」 picked, with their purchase record (FEATURE_2611_01 D7 · D15). */
    @EntityGraph(attributePaths = {"purchaseRecord"})
    List<StockMovement> findByIdIn(Collection<Long> ids);

    /** Rows that used one of these lots (FEATURE_2610_19 D149 ① (b)). */
    List<StockMovement> findBySourceMovementIdIn(Collection<Long> sourceMovementIds);

    /**
     * One day of the ledger for the daily in/out view (FEATURE_2610_19 D119 · D123), record order — with the purchase
     * record of each row's lot (FEATURE_2611_21 D20) — and the order rows a day correction moves together (D4).
     */
    @EntityGraph(attributePaths = {"product", "seller", "sourceMovement", "sourceMovement.seller",
            "sourceMovement.purchaseRecord", "purchaseRecord", "purchaseRecord.receipt", "orderLine", "orderLine.order",
            "orderLine.order.marketplaceAccount", "orderLine.order.marketplaceAccount.seller"})
    List<StockMovement> findByMovedOnOrderByIdAsc(LocalDate movedOn);

    /** Undecided intake rows of one product and day, record order (FEATURE_2610_19 D121 ⑥ · D130). */
    @EntityGraph(attributePaths = {"purchaseRecord"})
    @Query("""
            select m from StockMovement m
            where m.product.id = :productId and m.movedOn = :movedOn and m.seller is null
              and m.movementType = com.pms.domain.StockMovementType.STOCK_IN
            order by m.id asc
            """)
    List<StockMovement> findUndecidedIntake(@Param("productId") Long productId, @Param("movedOn") LocalDate movedOn);

    /**
     * FEATURE_2611_17 D22: the product's lots without a unit price — an incoming row that is not a return into another
     * lot (the same rows as {@code LotLedgerServiceImpl.isLot}), record order.
     */
    @Query("""
            select m from StockMovement m
            where m.product.id = :productId and m.quantity > 0 and m.sourceMovement is null and m.unitPrice is null
              and m.movementType in (com.pms.domain.StockMovementType.STOCK_IN,
                                     com.pms.domain.StockMovementType.RETURN_IN,
                                     com.pms.domain.StockMovementType.ADJUST)
            order by m.id asc
            """)
    List<StockMovement> findUnpricedLots(@Param("productId") Long productId);

    /** FEATURE_2611_17 D22 (start-up fill): products with a registered price that still have an unpriced lot. */
    @Query("""
            select distinct m.product.id from StockMovement m
            where m.quantity > 0 and m.sourceMovement is null and m.unitPrice is null and m.product.price is not null
              and m.movementType in (com.pms.domain.StockMovementType.STOCK_IN,
                                     com.pms.domain.StockMovementType.RETURN_IN,
                                     com.pms.domain.StockMovementType.ADJUST)
            """)
    List<Long> findPricedProductIdsWithUnpricedLots();
}
