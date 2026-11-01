package com.pms.repository;

import com.pms.domain.SellerDebt;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Borrowed outbound shares (FEATURE_2610_19 D51). Rewritten per product by {@code LotLedgerService.rebuildDebts}. */
@Repository
public interface SellerDebtRepository extends JpaRepository<SellerDebt, Long> {

    /** D111 · D132: the rewrite deletes the product's rows first. Bulk delete; no automatic clear — the callers keep using their loaded entities in the same transaction. */
    @Modifying(flushAutomatically = true)
    @Query("delete from SellerDebt d where d.product.id = :productId")
    int deleteByProductId(@Param("productId") Long productId);

    /** Debts of the given outbound rows — the notice of the confirm that just wrote them (D49). */
    @EntityGraph(attributePaths = {"lender"})
    List<SellerDebt> findByOutMovementIdInOrderByIdAsc(Collection<Long> outMovementIds);

    /** Every debt, for the pair balances (D51). */
    @EntityGraph(attributePaths = {"borrower", "lender"})
    @Query("select d from SellerDebt d")
    List<SellerDebt> findAllWithSellers();

    /** Debts between two sellers in either direction (D51 cause list). */
    @EntityGraph(attributePaths = {"borrower", "lender", "product", "outMovement", "outMovement.orderLine",
            "outMovement.orderLine.order", "sourceMovement", "sourceMovement.purchaseRecord", "shareRecord"})
    @Query("""
            select d from SellerDebt d
            where (d.borrower.id = :a and d.lender.id = :b) or (d.borrower.id = :b and d.lender.id = :a)
            order by d.movedOn desc, d.id desc
            """)
    List<SellerDebt> findBetween(@Param("a") Long sellerA, @Param("b") Long sellerB);

    /** FEATURE_2610_19 D150 (d): the product has a borrowed-goods debt. */
    boolean existsByProductId(Long productId);

    /** Debts of the given outbound rows with the source of their goods — the daily view's amounts (D123 ②). */
    @EntityGraph(attributePaths = {"lender"})
    List<SellerDebt> findByOutMovementIdIn(Collection<Long> outMovementIds);
}
