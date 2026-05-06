package com.pms.service;

import com.pms.domain.StockLog;
import com.pms.domain.StockType;
import com.pms.dto.request.StockBatchRequest;
import com.pms.dto.request.StockLogRequest;
import com.pms.dto.response.CurrentStockResponse;
import com.pms.dto.response.StockLogResponse;
import com.pms.exception.InsufficientStockException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.StockLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockLogServiceImpl implements StockLogService {

    private final StockLogRepository stockLogRepository;

    @Override
    @Transactional
    public StockLogResponse registerStock(StockLogRequest request) {
        // Convert barcodeId from String to Long
        Long barcodeIdLong = Long.parseLong(request.getBarcodeId());

        // Find previous stock log
        StockLog previousLog = stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(barcodeIdLong)
                .orElse(null);

        // Calculate inStock
        Integer currentInStock = previousLog != null ? previousLog.getInStock() : 0;
        Integer newInStock;

        if (StockType.IN.equals(request.getType())) {
            newInStock = currentInStock + request.getQuantity();
        } else {
            // OUT type
            if (request.getQuantity() > currentInStock) {
                throw new InsufficientStockException();
            }
            newInStock = currentInStock - request.getQuantity();
        }

        // Create and save StockLog
        StockLog stockLog = StockLog.builder()
                .barcodeId(barcodeIdLong)
                .inStock(newInStock)
                .name(request.getName())
                .stockAdd(StockType.IN.equals(request.getType()) ? request.getQuantity() : 0)
                .stockSub(StockType.OUT.equals(request.getType()) ? request.getQuantity() : 0)
                .build();

        StockLog saved = stockLogRepository.save(stockLog);

        return mapToResponse(saved);
    }

    @Override
    public CurrentStockResponse getCurrentStock(String barcodeId) {
        Long barcodeIdLong = Long.parseLong(barcodeId);

        StockLog latestLog = stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(barcodeIdLong)
                .orElseThrow(() -> new ResourceNotFoundException("Stock log", barcodeIdLong));

        return CurrentStockResponse.builder()
                .barcodeId(barcodeId)
                .inStock(latestLog.getInStock())
                .build();
    }

    @Override
    public Page<StockLogResponse> getStockLogs(String barcodeId, Pageable pageable) {
        Long barcodeIdLong = Long.parseLong(barcodeId);

        Page<StockLog> logs = stockLogRepository.findAllByBarcodeId(barcodeIdLong, pageable);

        return logs.map(this::mapToResponse);
    }

    @Override
    @Transactional
    public List<StockLogResponse> registerStockBatch(StockBatchRequest request) {
        // TODO: Implementation pending
        throw new UnsupportedOperationException("registerStockBatch not yet implemented");
    }

    private StockLogResponse mapToResponse(StockLog stockLog) {
        return StockLogResponse.builder()
                .stockId(stockLog.getStockId())
                .barcodeId(String.valueOf(stockLog.getBarcodeId()))
                .inStock(stockLog.getInStock())
                .name(stockLog.getName())
                .stockAdd(stockLog.getStockAdd())
                .stockSub(stockLog.getStockSub())
                .createdDate(stockLog.getCreatedDate())
                .build();
    }
}
