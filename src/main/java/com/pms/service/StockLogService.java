package com.pms.service;

import com.pms.dto.request.StockLogRequest;
import com.pms.dto.response.CurrentStockResponse;
import com.pms.dto.response.StockLogResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface StockLogService {

    StockLogResponse registerStock(StockLogRequest request);

    CurrentStockResponse getCurrentStock(String barcodeId);

    Page<StockLogResponse> getStockLogs(String barcodeId, Pageable pageable);
}
