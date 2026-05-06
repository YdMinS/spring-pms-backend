package com.pms.service;

import com.pms.domain.StockLog;
import com.pms.domain.StockType;
import com.pms.dto.request.StockLogRequest;
import com.pms.dto.response.CurrentStockResponse;
import com.pms.dto.response.StockLogResponse;
import com.pms.exception.InsufficientStockException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.StockLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * StockLogServiceTest - Unit tests for StockLogService
 *
 * RED Phase: All tests are currently failing (implementation pending)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StockLogServiceImpl - Unit Tests")
public class StockLogServiceTest {

    @Mock
    private StockLogRepository stockLogRepository;

    @InjectMocks
    private StockLogServiceImpl stockLogService;

    private static final String TEST_BARCODE_ID = "8801500152723";
    private static final Long TEST_BARCODE_ID_LONG = 8801500152723L;

    // ==================== registerStock - IN Type ====================

    @Test
    @DisplayName("Should register stock IN with valid request - returns StockLogResponse")
    public void testRegisterStock_IN_Success() {
        // Given
        StockLogRequest request = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.IN)
                .quantity(100)
                .name("Test Product")
                .build();

        StockLog savedLog = StockLog.builder()
                .stockId(1L)
                .barcodeId(TEST_BARCODE_ID_LONG)
                .inStock(100)
                .name("Test Product")
                .stockAdd(100)
                .stockSub(0)
                .createdDate(LocalDateTime.now())
                .build();

        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(TEST_BARCODE_ID_LONG))
                .thenReturn(Optional.empty());
        when(stockLogRepository.save(any(StockLog.class))).thenReturn(savedLog);

        // When
        StockLogResponse response = stockLogService.registerStock(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getStockId()).isEqualTo(1L);
        assertThat(response.getBarcodeId()).isEqualTo(TEST_BARCODE_ID);
        assertThat(response.getInStock()).isEqualTo(100);
        assertThat(response.getStockAdd()).isEqualTo(100);
        assertThat(response.getStockSub()).isEqualTo(0);
        assertThat(response.getName()).isEqualTo("Test Product");

        verify(stockLogRepository, times(1)).save(any(StockLog.class));
    }

    @Test
    @DisplayName("Should register stock IN with previous stock - inStock increases")
    public void testRegisterStock_IN_WithPreviousStock() {
        // Given
        StockLogRequest request = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.IN)
                .quantity(50)
                .name("Test Product")
                .build();

        StockLog previousLog = StockLog.builder()
                .stockId(1L)
                .barcodeId(TEST_BARCODE_ID_LONG)
                .inStock(100)
                .name("Test Product")
                .stockAdd(100)
                .stockSub(0)
                .createdDate(LocalDateTime.now().minusHours(1))
                .build();

        StockLog savedLog = StockLog.builder()
                .stockId(2L)
                .barcodeId(TEST_BARCODE_ID_LONG)
                .inStock(150)  // 100 + 50
                .name("Test Product")
                .stockAdd(50)
                .stockSub(0)
                .createdDate(LocalDateTime.now())
                .build();

        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(TEST_BARCODE_ID_LONG))
                .thenReturn(Optional.of(previousLog));
        when(stockLogRepository.save(any(StockLog.class))).thenReturn(savedLog);

        // When
        StockLogResponse response = stockLogService.registerStock(request);

        // Then
        assertThat(response.getInStock()).isEqualTo(150);
        assertThat(response.getStockAdd()).isEqualTo(50);
        assertThat(response.getStockSub()).isEqualTo(0);
    }

    // ==================== registerStock - OUT Type ====================

    @Test
    @DisplayName("Should register stock OUT with valid request - inStock decreases")
    public void testRegisterStock_OUT_Success() {
        // Given
        StockLogRequest request = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.OUT)
                .quantity(30)
                .name("Test Product")
                .build();

        StockLog previousLog = StockLog.builder()
                .stockId(1L)
                .barcodeId(TEST_BARCODE_ID_LONG)
                .inStock(100)
                .name("Test Product")
                .stockAdd(100)
                .stockSub(0)
                .createdDate(LocalDateTime.now().minusHours(1))
                .build();

        StockLog savedLog = StockLog.builder()
                .stockId(2L)
                .barcodeId(TEST_BARCODE_ID_LONG)
                .inStock(70)  // 100 - 30
                .name("Test Product")
                .stockAdd(0)
                .stockSub(30)
                .createdDate(LocalDateTime.now())
                .build();

        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(TEST_BARCODE_ID_LONG))
                .thenReturn(Optional.of(previousLog));
        when(stockLogRepository.save(any(StockLog.class))).thenReturn(savedLog);

        // When
        StockLogResponse response = stockLogService.registerStock(request);

        // Then
        assertThat(response.getInStock()).isEqualTo(70);
        assertThat(response.getStockAdd()).isEqualTo(0);
        assertThat(response.getStockSub()).isEqualTo(30);
    }

    @Test
    @DisplayName("Should throw InsufficientStockException when OUT quantity exceeds inStock")
    public void testRegisterStock_OUT_InsufficientStock() {
        // Given
        StockLogRequest request = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.OUT)
                .quantity(150)  // More than available
                .name("Test Product")
                .build();

        StockLog previousLog = StockLog.builder()
                .stockId(1L)
                .barcodeId(TEST_BARCODE_ID_LONG)
                .inStock(100)  // Only 100 available
                .name("Test Product")
                .stockAdd(100)
                .stockSub(0)
                .createdDate(LocalDateTime.now().minusHours(1))
                .build();

        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(TEST_BARCODE_ID_LONG))
                .thenReturn(Optional.of(previousLog));

        // When & Then
        assertThatThrownBy(() -> stockLogService.registerStock(request))
                .isInstanceOf(InsufficientStockException.class);

        verify(stockLogRepository, never()).save(any(StockLog.class));
    }

    // ==================== getCurrentStock ====================

    @Test
    @DisplayName("Should return current stock when valid barcodeId provided")
    public void testGetCurrentStock_Success() {
        // Given
        StockLog latestLog = StockLog.builder()
                .stockId(1L)
                .barcodeId(TEST_BARCODE_ID_LONG)
                .inStock(100)
                .name("Test Product")
                .stockAdd(100)
                .stockSub(0)
                .createdDate(LocalDateTime.now())
                .build();

        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(TEST_BARCODE_ID_LONG))
                .thenReturn(Optional.of(latestLog));

        // When
        CurrentStockResponse response = stockLogService.getCurrentStock(TEST_BARCODE_ID);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getBarcodeId()).isEqualTo(TEST_BARCODE_ID);
        assertThat(response.getInStock()).isEqualTo(100);
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when no stock log found")
    public void testGetCurrentStock_NotFound() {
        // Given
        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(TEST_BARCODE_ID_LONG))
                .thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> stockLogService.getCurrentStock(TEST_BARCODE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================== getStockLogs ====================

    @Test
    @DisplayName("Should return paged stock logs filtered by barcodeId")
    public void testGetStockLogs_Success() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);

        List<StockLog> stockLogs = new ArrayList<>();
        stockLogs.add(StockLog.builder()
                .stockId(1L)
                .barcodeId(TEST_BARCODE_ID_LONG)
                .inStock(100)
                .name("Test Product")
                .stockAdd(100)
                .stockSub(0)
                .createdDate(LocalDateTime.now().minusHours(2))
                .build());
        stockLogs.add(StockLog.builder()
                .stockId(2L)
                .barcodeId(TEST_BARCODE_ID_LONG)
                .inStock(50)
                .name("Test Product")
                .stockAdd(0)
                .stockSub(50)
                .createdDate(LocalDateTime.now().minusHours(1))
                .build());

        Page<StockLog> pageResult = new PageImpl<>(stockLogs, pageable, 2);

        when(stockLogRepository.findAllByBarcodeId(TEST_BARCODE_ID_LONG, pageable))
                .thenReturn(pageResult);

        // When
        Page<StockLogResponse> response = stockLogService.getStockLogs(TEST_BARCODE_ID, pageable);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getTotalElements()).isEqualTo(2);
        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getContent().get(0).getInStock()).isEqualTo(100);
        assertThat(response.getContent().get(1).getInStock()).isEqualTo(50);
    }

    @Test
    @DisplayName("Should return empty page when no stock logs exist")
    public void testGetStockLogs_EmptyResult() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        Page<StockLog> emptyPage = new PageImpl<>(new ArrayList<>(), pageable, 0);

        when(stockLogRepository.findAllByBarcodeId(TEST_BARCODE_ID_LONG, pageable))
                .thenReturn(emptyPage);

        // When
        Page<StockLogResponse> response = stockLogService.getStockLogs(TEST_BARCODE_ID, pageable);

        // Then
        assertThat(response.getTotalElements()).isEqualTo(0);
        assertThat(response.getContent()).isEmpty();
    }
}
