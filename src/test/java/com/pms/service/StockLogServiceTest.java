package com.pms.service;

import com.pms.domain.Product;
import com.pms.domain.StockLog;
import com.pms.domain.StockType;
import com.pms.dto.request.StockBatchItem;
import com.pms.dto.request.StockBatchRequest;
import com.pms.dto.request.StockLogRequest;
import com.pms.dto.response.CurrentStockResponse;
import com.pms.dto.response.StockLogResponse;
import com.pms.exception.InsufficientStockException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProductRepository;
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

    @Mock
    private ProductRepository productRepository;

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
                .name("Old Product Name")
                .stockAdd(100)
                .stockSub(0)
                .createdDate(LocalDateTime.now())
                .build();

        Product product = Product.builder()
                .id(1L)
                .barcodeId(TEST_BARCODE_ID)
                .productName("Current Product Name")
                .name("Test")
                .active(true)
                .build();

        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(TEST_BARCODE_ID_LONG))
                .thenReturn(Optional.of(latestLog));
        when(productRepository.findByBarcodeId(TEST_BARCODE_ID))
                .thenReturn(Optional.of(product));

        // When
        CurrentStockResponse response = stockLogService.getCurrentStock(TEST_BARCODE_ID);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getBarcodeId()).isEqualTo(TEST_BARCODE_ID);
        assertThat(response.getProductName()).isEqualTo("Current Product Name");
        assertThat(response.getInStock()).isEqualTo(100);
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when no stock log and no product found")
    public void testGetCurrentStock_NotFound() {
        // Given
        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(TEST_BARCODE_ID_LONG))
                .thenReturn(Optional.empty());
        when(productRepository.findByBarcodeId(TEST_BARCODE_ID))
                .thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> stockLogService.getCurrentStock(TEST_BARCODE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Should return zero stock when no stock log but product exists")
    public void testGetCurrentStock_NoStockLog_ProductExists() {
        // Given
        Product product = Product.builder()
                .id(1L)
                .barcodeId(TEST_BARCODE_ID)
                .productName("New Product")
                .name("Test")
                .active(true)
                .build();

        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(TEST_BARCODE_ID_LONG))
                .thenReturn(Optional.empty());
        when(productRepository.findByBarcodeId(TEST_BARCODE_ID))
                .thenReturn(Optional.of(product));

        // When
        CurrentStockResponse response = stockLogService.getCurrentStock(TEST_BARCODE_ID);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getBarcodeId()).isEqualTo(TEST_BARCODE_ID);
        assertThat(response.getProductName()).isEqualTo("New Product");
        assertThat(response.getInStock()).isEqualTo(0);
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

    // ==================== registerStockBatch - IN Type ====================

    @Test
    @DisplayName("Batch IN - Should register stock for 3 items with IN type")
    public void testRegisterStockBatch_IN_Success() {
        // Given
        List<StockBatchItem> items = List.of(
                StockBatchItem.builder().barcodeId("8801500152723").quantity(100).name("Product A").build(),
                StockBatchItem.builder().barcodeId("8801500152724").quantity(50).name("Product B").build(),
                StockBatchItem.builder().barcodeId("8801500152725").quantity(75).name("Product C").build()
        );

        StockBatchRequest request = StockBatchRequest.builder()
                .type(StockType.IN)
                .items(items)
                .build();

        List<StockLog> savedLogs = List.of(
                StockLog.builder().stockId(1L).barcodeId(8801500152723L).inStock(100).name("Product A")
                        .stockAdd(100).stockSub(0).createdDate(LocalDateTime.now()).build(),
                StockLog.builder().stockId(2L).barcodeId(8801500152724L).inStock(50).name("Product B")
                        .stockAdd(50).stockSub(0).createdDate(LocalDateTime.now()).build(),
                StockLog.builder().stockId(3L).barcodeId(8801500152725L).inStock(75).name("Product C")
                        .stockAdd(75).stockSub(0).createdDate(LocalDateTime.now()).build()
        );

        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(8801500152723L)).thenReturn(Optional.empty());
        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(8801500152724L)).thenReturn(Optional.empty());
        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(8801500152725L)).thenReturn(Optional.empty());
        when(stockLogRepository.save(any(StockLog.class))).thenReturn(savedLogs.get(0), savedLogs.get(1), savedLogs.get(2));

        // When
        List<StockLogResponse> responses = stockLogService.registerStockBatch(request);

        // Then
        assertThat(responses).hasSize(3);
        assertThat(responses.get(0).getStockAdd()).isEqualTo(100);
        assertThat(responses.get(0).getStockSub()).isEqualTo(0);
        assertThat(responses.get(1).getStockAdd()).isEqualTo(50);
        assertThat(responses.get(1).getStockSub()).isEqualTo(0);
        assertThat(responses.get(2).getStockAdd()).isEqualTo(75);
        assertThat(responses.get(2).getStockSub()).isEqualTo(0);

        verify(stockLogRepository, times(3)).save(any(StockLog.class));
    }

    // ==================== registerStockBatch - OUT Type ====================

    @Test
    @DisplayName("Batch OUT - Should register stock OUT for 2 items with previous stock")
    public void testRegisterStockBatch_OUT_Success() {
        // Given
        StockLog previousLog1 = StockLog.builder().stockId(1L).barcodeId(8801500152723L).inStock(100)
                .name("Product A").stockAdd(100).stockSub(0).createdDate(LocalDateTime.now().minusHours(1)).build();
        StockLog previousLog2 = StockLog.builder().stockId(2L).barcodeId(8801500152724L).inStock(50)
                .name("Product B").stockAdd(50).stockSub(0).createdDate(LocalDateTime.now().minusHours(1)).build();

        List<StockBatchItem> items = List.of(
                StockBatchItem.builder().barcodeId("8801500152723").quantity(30).name("Product A").build(),
                StockBatchItem.builder().barcodeId("8801500152724").quantity(20).name("Product B").build()
        );

        StockBatchRequest request = StockBatchRequest.builder()
                .type(StockType.OUT)
                .items(items)
                .build();

        List<StockLog> savedLogs = List.of(
                StockLog.builder().stockId(3L).barcodeId(8801500152723L).inStock(70).name("Product A")
                        .stockAdd(0).stockSub(30).createdDate(LocalDateTime.now()).build(),
                StockLog.builder().stockId(4L).barcodeId(8801500152724L).inStock(30).name("Product B")
                        .stockAdd(0).stockSub(20).createdDate(LocalDateTime.now()).build()
        );

        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(8801500152723L)).thenReturn(Optional.of(previousLog1));
        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(8801500152724L)).thenReturn(Optional.of(previousLog2));
        when(stockLogRepository.save(any(StockLog.class))).thenReturn(savedLogs.get(0), savedLogs.get(1));

        // When
        List<StockLogResponse> responses = stockLogService.registerStockBatch(request);

        // Then
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getInStock()).isEqualTo(70);
        assertThat(responses.get(0).getStockAdd()).isEqualTo(0);
        assertThat(responses.get(0).getStockSub()).isEqualTo(30);
        assertThat(responses.get(1).getInStock()).isEqualTo(30);
        assertThat(responses.get(1).getStockAdd()).isEqualTo(0);
        assertThat(responses.get(1).getStockSub()).isEqualTo(20);

        verify(stockLogRepository, times(2)).save(any(StockLog.class));
    }

    @Test
    @DisplayName("Batch OUT - Insufficient stock in one item triggers rollback")
    public void testRegisterStockBatch_OUT_InsufficientStock_Rollback() {
        // Given
        StockLog previousLog = StockLog.builder().stockId(1L).barcodeId(8801500152723L).inStock(50)
                .name("Product A").stockAdd(50).stockSub(0).createdDate(LocalDateTime.now().minusHours(1)).build();

        List<StockBatchItem> items = List.of(
                StockBatchItem.builder().barcodeId("8801500152723").quantity(60).name("Product A").build(),
                StockBatchItem.builder().barcodeId("8801500152724").quantity(20).name("Product B").build()
        );

        StockBatchRequest request = StockBatchRequest.builder()
                .type(StockType.OUT)
                .items(items)
                .build();

        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(8801500152723L)).thenReturn(Optional.of(previousLog));

        // When & Then
        assertThatThrownBy(() -> stockLogService.registerStockBatch(request))
                .isInstanceOf(InsufficientStockException.class);

        verify(stockLogRepository, never()).save(any(StockLog.class));
    }

    @Test
    @DisplayName("Batch IN - Duplicate barcode ID processed sequentially")
    public void testRegisterStockBatch_IN_DuplicateBarcodeId() {
        // Given
        List<StockBatchItem> items = List.of(
                StockBatchItem.builder().barcodeId("8801500152723").quantity(100).name("Product A").build(),
                StockBatchItem.builder().barcodeId("8801500152723").quantity(50).name("Product A").build()
        );

        StockBatchRequest request = StockBatchRequest.builder()
                .type(StockType.IN)
                .items(items)
                .build();

        StockLog firstSave = StockLog.builder().stockId(1L).barcodeId(8801500152723L).inStock(100).name("Product A")
                .stockAdd(100).stockSub(0).createdDate(LocalDateTime.now()).build();
        StockLog secondSave = StockLog.builder().stockId(2L).barcodeId(8801500152723L).inStock(150).name("Product A")
                .stockAdd(50).stockSub(0).createdDate(LocalDateTime.now()).build();

        when(stockLogRepository.findTopByBarcodeIdOrderByCreatedDateDesc(8801500152723L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(firstSave));
        when(stockLogRepository.save(any(StockLog.class))).thenReturn(firstSave, secondSave);

        // When
        List<StockLogResponse> responses = stockLogService.registerStockBatch(request);

        // Then
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getInStock()).isEqualTo(100);
        assertThat(responses.get(1).getInStock()).isEqualTo(150);
        verify(stockLogRepository, times(2)).save(any(StockLog.class));
    }

}
