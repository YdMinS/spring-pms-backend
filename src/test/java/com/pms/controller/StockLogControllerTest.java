package com.pms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.common.BaseIntegrationTest;
import com.pms.domain.StockType;
import com.pms.dto.request.StockBatchItem;
import com.pms.dto.request.StockBatchRequest;
import com.pms.dto.request.StockLogRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * StockLogControllerTest - Integration tests for StockLog REST API
 *
 * RED Phase: All tests are currently failing (implementation pending)
 */
@DisplayName("StockLogController - Integration Tests")
@ActiveProfiles("test")
public class StockLogControllerTest extends BaseIntegrationTest {

    private static final String TEST_BARCODE_ID = "8801500152723";
    private static final String API_STOCK_BASE = "/api/stock";

    // ==================== POST /api/stock - Register Stock IN ====================

    @Test
    @DisplayName("POST /api/stock - Register stock IN with USER token - returns 201")
    public void testRegisterStockIN_Success() throws Exception {
        // Given
        StockLogRequest request = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.IN)
                .quantity(100)
                .name("Test Product")
                .build();

        // When & Then
        mockMvc.perform(post(API_STOCK_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.barcodeId").value(TEST_BARCODE_ID))
                .andExpect(jsonPath("$.data.inStock").value(100))
                .andExpect(jsonPath("$.data.stockAdd").value(100))
                .andExpect(jsonPath("$.data.stockSub").value(0));
    }

    @Test
    @DisplayName("POST /api/stock - Register stock OUT with USER token - returns 201")
    public void testRegisterStockOUT_Success() throws Exception {
        // Given
        // First register IN stock
        StockLogRequest inRequest = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.IN)
                .quantity(100)
                .name("Test Product")
                .build();

        mockMvc.perform(post(API_STOCK_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(inRequest))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated());

        // Then register OUT stock
        StockLogRequest outRequest = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.OUT)
                .quantity(30)
                .name("Test Product")
                .build();

        // When & Then
        mockMvc.perform(post(API_STOCK_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(outRequest))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.inStock").value(70))
                .andExpect(jsonPath("$.data.stockAdd").value(0))
                .andExpect(jsonPath("$.data.stockSub").value(30));
    }

    @Test
    @DisplayName("POST /api/stock - OUT with insufficient stock - returns 409")
    public void testRegisterStockOUT_InsufficientStock() throws Exception {
        // Given
        StockLogRequest inRequest = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.IN)
                .quantity(50)
                .name("Test Product")
                .build();

        mockMvc.perform(post(API_STOCK_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(inRequest))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated());

        StockLogRequest outRequest = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.OUT)
                .quantity(100)  // More than available
                .name("Test Product")
                .build();

        // When & Then
        mockMvc.perform(post(API_STOCK_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(outRequest))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /api/stock - Missing authentication token - returns 401")
    public void testRegisterStock_NoToken() throws Exception {
        // Given
        StockLogRequest request = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.IN)
                .quantity(100)
                .name("Test Product")
                .build();

        // When & Then
        mockMvc.perform(post(API_STOCK_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/stock - Invalid type (Jackson parsing) - returns 400")
    public void testRegisterStock_InvalidType() throws Exception {
        // Given - Invalid JSON with invalid type value
        String requestJson = "{" +
                "\"barcodeId\":\"" + TEST_BARCODE_ID + "\"," +
                "\"type\":\"INVALID\"," +
                "\"quantity\":100," +
                "\"name\":\"Test Product\"" +
                "}";

        // When & Then - Jackson fails to parse enum, returns 400
        mockMvc.perform(post(API_STOCK_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson)
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/stock - Missing barcodeId field - returns 400")
    public void testRegisterStock_MissingBarcodeId() throws Exception {
        // Given
        String requestJson = "{" +
                "\"type\":\"IN\"," +
                "\"quantity\":100," +
                "\"name\":\"Test Product\"" +
                "}";

        // When & Then
        mockMvc.perform(post(API_STOCK_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson)
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest());
    }

    // ==================== GET /api/stock - List Stock Logs ====================

    @Test
    @DisplayName("GET /api/stock with barcodeId param - returns 200 with paged results")
    public void testGetStockLogs_WithBarcodeId() throws Exception {
        // Given
        StockLogRequest request = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.IN)
                .quantity(100)
                .name("Test Product")
                .build();

        mockMvc.perform(post(API_STOCK_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated());

        // When & Then
        mockMvc.perform(get(API_STOCK_BASE)
                .param("barcodeId", TEST_BARCODE_ID)
                .param("page", "0")
                .param("size", "10")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").isNumber());
    }

    @Test
    @DisplayName("GET /api/stock without filters - returns 200 with all logs")
    public void testGetStockLogs_AllLogs() throws Exception {
        // Given
        StockLogRequest request = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.IN)
                .quantity(100)
                .name("Test Product")
                .build();

        mockMvc.perform(post(API_STOCK_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated());

        // When & Then
        mockMvc.perform(get(API_STOCK_BASE)
                .param("barcodeId", TEST_BARCODE_ID)
                .param("page", "0")
                .param("size", "10")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").isNumber());
    }

    @Test
    @DisplayName("GET /api/stock with startDate and endDate - returns 200 with filtered results")
    public void testGetStockLogs_WithDateRange() throws Exception {
        // Given
        StockLogRequest request = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.IN)
                .quantity(100)
                .name("Test Product")
                .build();

        mockMvc.perform(post(API_STOCK_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated());

        // When & Then
        mockMvc.perform(get(API_STOCK_BASE)
                .param("page", "0")
                .param("size", "10")
                .param("startDate", "2026-05-01")
                .param("endDate", "2026-05-07")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    @DisplayName("GET /api/stock with startDate only - returns 200 with logs from startDate to today")
    public void testGetStockLogs_WithStartDateOnly() throws Exception {
        // Given
        StockLogRequest request = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.IN)
                .quantity(100)
                .name("Test Product")
                .build();

        mockMvc.perform(post(API_STOCK_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated());

        // When & Then
        mockMvc.perform(get(API_STOCK_BASE)
                .param("page", "0")
                .param("size", "10")
                .param("startDate", "2026-05-01")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    @DisplayName("GET /api/stock with productName filter - returns 200 with matching product logs")
    public void testGetStockLogs_WithProductNameFilter() throws Exception {
        // Given
        StockLogRequest request = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.IN)
                .quantity(100)
                .name("Arabica Coffee")
                .build();

        mockMvc.perform(post(API_STOCK_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated());

        // When & Then - Search with keyword "Coffee"
        mockMvc.perform(get(API_STOCK_BASE)
                .param("page", "0")
                .param("size", "10")
                .param("productName", "Coffee")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content[0].productName").value("Arabica Coffee"));
    }

    @Test
    @DisplayName("GET /api/stock - Missing authentication token - returns 401")
    public void testGetStockLogs_NoToken() throws Exception {
        // When & Then
        mockMvc.perform(get(API_STOCK_BASE)
                .param("barcodeId", TEST_BARCODE_ID))
                .andExpect(status().isUnauthorized());
    }

    // ==================== GET /api/stock/{barcodeId} - Get Current Stock ====================

    @Test
    @DisplayName("GET /api/stock/{barcodeId} - Valid barcodeId - returns 200 with current stock")
    public void testGetCurrentStock_Success() throws Exception {
        // Given
        StockLogRequest request = StockLogRequest.builder()
                .barcodeId(TEST_BARCODE_ID)
                .type(StockType.IN)
                .quantity(100)
                .name("Test Product")
                .build();

        mockMvc.perform(post(API_STOCK_BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated());

        // When & Then
        mockMvc.perform(get(API_STOCK_BASE + "/" + TEST_BARCODE_ID)
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.barcodeId").value(TEST_BARCODE_ID))
                .andExpect(jsonPath("$.data.inStock").value(100));
    }

    @Test
    @DisplayName("GET /api/stock/{barcodeId} - Invalid barcodeId (not found) - returns 404")
    public void testGetCurrentStock_NotFound() throws Exception {
        // When & Then
        mockMvc.perform(get(API_STOCK_BASE + "/9999999999999")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/stock/{barcodeId} - Missing authentication token - returns 401")
    public void testGetCurrentStock_NoToken() throws Exception {
        // When & Then
        mockMvc.perform(get(API_STOCK_BASE + "/" + TEST_BARCODE_ID))
                .andExpect(status().isUnauthorized());
    }

    // ==================== POST /api/stock/batch - Register Stock Batch ====================

    @Test
    @DisplayName("POST /api/stock/batch - Register batch IN with USER token - returns 201")
    public void testRegisterStockBatch_IN_Success() throws Exception {
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

        // When & Then
        mockMvc.perform(post(API_STOCK_BASE + "/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].stockAdd").value(100))
                .andExpect(jsonPath("$.data.items[0].stockSub").value(0));
    }

    @Test
    @DisplayName("POST /api/stock/batch - Register batch OUT with USER token - returns 201")
    public void testRegisterStockBatch_OUT_Success() throws Exception {
        // Given - First register IN stock
        List<StockBatchItem> inItems = List.of(
                StockBatchItem.builder().barcodeId("8801500152723").quantity(100).name("Product A").build(),
                StockBatchItem.builder().barcodeId("8801500152724").quantity(50).name("Product B").build()
        );

        StockBatchRequest inRequest = StockBatchRequest.builder()
                .type(StockType.IN)
                .items(inItems)
                .build();

        mockMvc.perform(post(API_STOCK_BASE + "/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(inRequest))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated());

        // Then register OUT batch
        List<StockBatchItem> outItems = List.of(
                StockBatchItem.builder().barcodeId("8801500152723").quantity(30).name("Product A").build(),
                StockBatchItem.builder().barcodeId("8801500152724").quantity(20).name("Product B").build()
        );

        StockBatchRequest outRequest = StockBatchRequest.builder()
                .type(StockType.OUT)
                .items(outItems)
                .build();

        // When & Then
        mockMvc.perform(post(API_STOCK_BASE + "/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(outRequest))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].stockAdd").value(0))
                .andExpect(jsonPath("$.data.items[0].stockSub").value(30));
    }

    @Test
    @DisplayName("POST /api/stock/batch - OUT with insufficient stock - returns 409")
    public void testRegisterStockBatch_OUT_InsufficientStock() throws Exception {
        // Given - First register IN stock
        List<StockBatchItem> inItems = List.of(
                StockBatchItem.builder().barcodeId("8801500152723").quantity(50).name("Product A").build()
        );

        StockBatchRequest inRequest = StockBatchRequest.builder()
                .type(StockType.IN)
                .items(inItems)
                .build();

        mockMvc.perform(post(API_STOCK_BASE + "/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(inRequest))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isCreated());

        // Then attempt OUT with insufficient stock
        List<StockBatchItem> outItems = List.of(
                StockBatchItem.builder().barcodeId("8801500152723").quantity(100).name("Product A").build()
        );

        StockBatchRequest outRequest = StockBatchRequest.builder()
                .type(StockType.OUT)
                .items(outItems)
                .build();

        // When & Then
        mockMvc.perform(post(API_STOCK_BASE + "/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(outRequest))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /api/stock/batch - Empty items list - returns 400")
    public void testRegisterStockBatch_EmptyItems() throws Exception {
        // Given
        StockBatchRequest request = StockBatchRequest.builder()
                .type(StockType.IN)
                .items(new ArrayList<>())  // Empty list
                .build();

        // When & Then
        mockMvc.perform(post(API_STOCK_BASE + "/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/stock/batch - Invalid type enum - returns 400")
    public void testRegisterStockBatch_InvalidType() throws Exception {
        // Given - Invalid JSON with invalid type value
        String requestJson = "{" +
                "\"type\":\"INVALID\"," +
                "\"items\":[" +
                "{\"barcodeId\":\"8801500152723\",\"quantity\":100,\"name\":\"Product A\"}" +
                "]" +
                "}";

        // When & Then
        mockMvc.perform(post(API_STOCK_BASE + "/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson)
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/stock/batch - Missing authentication token - returns 401")
    public void testRegisterStockBatch_NoToken() throws Exception {
        // Given
        List<StockBatchItem> items = List.of(
                StockBatchItem.builder().barcodeId("8801500152723").quantity(100).name("Product A").build()
        );

        StockBatchRequest request = StockBatchRequest.builder()
                .type(StockType.IN)
                .items(items)
                .build();

        // When & Then
        mockMvc.perform(post(API_STOCK_BASE + "/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
