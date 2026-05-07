package com.pms.service;

import com.pms.dto.request.StockBatchRequest;
import com.pms.dto.request.StockLogRequest;
import com.pms.dto.response.CurrentStockResponse;
import com.pms.dto.response.StockLogResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

public interface StockLogService {

    StockLogResponse registerStock(StockLogRequest request);

    CurrentStockResponse getCurrentStock(String barcodeId);

    Page<StockLogResponse> getStockLogs(String barcodeId, LocalDate startDate, LocalDate endDate, Pageable pageable);

    List<StockLogResponse> registerStockBatch(StockBatchRequest request);
}
