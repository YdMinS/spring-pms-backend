package com.pms.repository;

import com.pms.domain.PurchaseCheck;
import com.pms.domain.PurchaseCheckStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** Purchase list processing records (FEATURE_2610_09 / D62). Tenant-filtered by {@code @TenantId}. */
public interface PurchaseCheckRepository extends JpaRepository<PurchaseCheck, Long> {

    /** Records still in effect for the listed products — the list builder reads them in one query. */
    List<PurchaseCheck> findByProduct_IdInAndCancelledAtIsNull(Collection<Long> productIds);

    /** Records still in effect for one share — the check endpoint cancels the screen-made ones among them. */
    List<PurchaseCheck> findByProduct_IdAndSeller_IdAndCancelledAtIsNull(Long productId, Long sellerId);

    /** NEED_ZERO records still in effect — the extract compares them with the new needed quantities (D85). */
    List<PurchaseCheck> findByStatusAndCancelledAtIsNull(PurchaseCheckStatus status);
}
