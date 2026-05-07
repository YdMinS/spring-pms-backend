package com.pms.repository;

import com.pms.domain.StockLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface StockLogRepository extends JpaRepository<StockLog, Long> {

    Optional<StockLog> findTopByBarcodeIdOrderByCreatedDateDesc(Long barcodeId);

    Page<StockLog> findAllByBarcodeId(Long barcodeId, Pageable pageable);

    @Query("SELECT s FROM StockLog s WHERE " +
           "(:barcodeId IS NULL OR s.barcodeId = :barcodeId) AND " +
           "(:startDate IS NULL OR s.createdDate >= :startDate) AND " +
           "(:endDate IS NULL OR s.createdDate <= :endDate) " +
           "ORDER BY s.createdDate DESC")
    Page<StockLog> findByBarcodeIdAndDateRange(
            @Param("barcodeId") Long barcodeId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);
}
