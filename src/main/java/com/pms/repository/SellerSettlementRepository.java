package com.pms.repository;

import com.pms.domain.SellerSettlement;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Cash settlements between sellers (FEATURE_2610_19 D90). */
@Repository
public interface SellerSettlementRepository extends JpaRepository<SellerSettlement, Long> {

    @EntityGraph(attributePaths = {"payer", "receiver"})
    @Query("select s from SellerSettlement s")
    List<SellerSettlement> findAllWithSellers();

    @EntityGraph(attributePaths = {"payer", "receiver"})
    @Query("""
            select s from SellerSettlement s
            where (s.payer.id = :a and s.receiver.id = :b) or (s.payer.id = :b and s.receiver.id = :a)
            order by s.settledOn desc, s.id desc
            """)
    List<SellerSettlement> findBetween(@Param("a") Long sellerA, @Param("b") Long sellerB);
}
