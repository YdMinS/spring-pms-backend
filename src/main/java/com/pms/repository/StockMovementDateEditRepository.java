package com.pms.repository;

import com.pms.domain.StockMovementDateEdit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** Day corrections of stock ledger rows (FEATURE_2611_21 D7 · D24). */
public interface StockMovementDateEditRepository extends JpaRepository<StockMovementDateEdit, Long> {

    /** The corrections of the given rows, newest first (D24). */
    List<StockMovementDateEdit> findByStockMovementIdInOrderByIdDesc(Collection<Long> stockMovementIds);
}
