package com.pms.repository;

import com.pms.domain.StockLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StockLogRepository extends JpaRepository<StockLog, Long> {

    Optional<StockLog> findTopByBarcodeIdOrderByCreatedDateDesc(Long barcodeId);

    Page<StockLog> findAllByBarcodeId(Long barcodeId, Pageable pageable);
}
