package com.pms.repository;

import com.pms.domain.ProductPurchasePlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Product ↔ purchase place links (FEATURE_2609_76 / D20). No tenant column — callers pass product ids or
 * place ids they already read through a tenant-filtered repository.
 */
public interface ProductPurchasePlaceRepository extends JpaRepository<ProductPurchasePlace, Long> {

    /**
     * Links of these products with the place loaded, in list order (place {@code sortOrder}, then id). One query
     * for a whole page of products — never call it per product.
     */
    @Query("select l from ProductPurchasePlace l join fetch l.purchasePlace pp "
            + "where l.product.id in :productIds order by pp.sortOrder asc, pp.id asc")
    List<ProductPurchasePlace> findWithPlaceByProductIdIn(@Param("productIds") Collection<Long> productIds);

    /**
     * {@code [placeId, count]} of ACTIVE products per place (D9). 🔴 Soft-deleted products are not "using" a
     * place — counting them would make a place undeletable because of a product nobody can see.
     */
    @Query("select l.purchasePlace.id, count(l) from ProductPurchasePlace l "
            + "where l.purchasePlace.id in :placeIds and l.product.active = true group by l.purchasePlace.id")
    List<Object[]> countActiveProductsByPlaceIdIn(@Param("placeIds") Collection<Long> placeIds);

    /** Links of one place — only soft-deleted products' links are left when a delete gets this far. */
    @Modifying(flushAutomatically = true)
    @Query("delete from ProductPurchasePlace l where l.purchasePlace.id = :placeId")
    int deleteByPurchasePlaceId(@Param("placeId") Long placeId);
}
