package com.pms.repository;

import com.pms.domain.PurchaseReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseReceiptRepository extends JpaRepository<PurchaseReceipt, Long> {

    /**
     * Receipt list (FEATURE_2610_18 D94): purchase day in [from, to] (inclusive), payer seller ({@code sellerId}
     * null = every seller), a line whose product name contains {@code productName} (null = any), newest day first.
     *
     * <p>{@code left join fetch rc.card}: the card alias shows even for a hidden card (D91).</p>
     */
    @Query("""
            select rc from PurchaseReceipt rc join fetch rc.payerSeller left join fetch rc.card
            where rc.purchasedOn >= :from and rc.purchasedOn <= :to
              and (:sellerId is null or rc.payerSeller.id = :sellerId)
              and (:productName is null or exists (
                    select r.id from PurchaseRecord r
                    where r.receipt = rc
                      and lower(r.product.productName) like lower(concat('%', :productName, '%'))))
            order by rc.purchasedOn desc, rc.id desc
            """)
    List<PurchaseReceipt> search(@Param("from") LocalDate from,
                                 @Param("to") LocalDate to,
                                 @Param("sellerId") Long sellerId,
                                 @Param("productName") String productName);

    /** One receipt with its payer seller and card (hidden card included, D91). */
    @Query("select rc from PurchaseReceipt rc join fetch rc.payerSeller left join fetch rc.card where rc.id = :id")
    Optional<PurchaseReceipt> findDetailById(@Param("id") Long id);
}
