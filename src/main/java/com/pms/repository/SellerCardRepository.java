package com.pms.repository;

import com.pms.domain.SellerCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SellerCardRepository extends JpaRepository<SellerCard, Long> {

    /**
     * Visible cards of every seller, seller id then card id (FEATURE_2610_18 D52 · D91) — the receipt popup's card
     * list and the seller screen's card section. Hidden cards ({@code hiddenAt} set) are left out.
     *
     * <p>{@code join fetch c.seller}: every row shows its seller name.</p>
     */
    @Query("select c from SellerCard c join fetch c.seller where c.hiddenAt is null order by c.seller.id, c.id")
    List<SellerCard> findVisible();
}
