package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.StockBatchRequest;
import com.pms.dto.request.StockLogRequest;
import com.pms.dto.response.CurrentStockResponse;
import com.pms.dto.response.StockLogResponse;
import com.pms.service.StockLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stock")
@RequiredArgsConstructor
@Tag(name = "Stock", description = "Stock log management API")
public class StockLogController {

    private final StockLogService stockLogService;

    /**
     * Register stock IN/OUT
     *
     * @param request StockLogRequest with type (IN/OUT), quantity, barcodeId, name
     * @return HTTP 201 Created with StockLogResponse
     */
    @PostMapping
    @Operation(summary = "Register stock IN/OUT", description = "Register stock IN or OUT transaction (USER, ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "201", description = "Stock registered successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error or invalid enum type",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "409", description = "Insufficient stock (OUT only)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<StockLogResponse>> registerStock(
            @Valid @RequestBody StockLogRequest request) {
        StockLogResponse response = stockLogService.registerStock(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDTO.success(response));
    }

    /**
     * Register batch stock IN/OUT for multiple products
     *
     * @param request StockBatchRequest with type (IN/OUT) and items list
     * @return HTTP 201 Created with list of StockLogResponse
     */
    @PostMapping("/batch")
    @Operation(summary = "Register stock batch", description = "Bulk register stock IN or OUT for multiple products (USER, ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "201", description = "Batch stock registered successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error or invalid enum type",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "409", description = "Insufficient stock (OUT only)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<Map<String, List<StockLogResponse>>>> registerStockBatch(
            @Valid @RequestBody StockBatchRequest request) {
        List<StockLogResponse> responses = stockLogService.registerStockBatch(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDTO.success(Map.of("items", responses)));
    }

    /**
     * Get stock logs with pagination and optional filtering by barcodeId and date range
     *
     * @param barcodeId Barcode ID to filter (optional)
     * @param startDate Start date for filtering (optional, format: yyyy-MM-dd)
     *                  If provided without endDate, filters from startDate to today
     * @param endDate End date for filtering (optional, format: yyyy-MM-dd)
     *                If provided without startDate, filters from beginning to endDate
     * @param page Page number (0-indexed, default: 0)
     * @param size Page size (default: 20)
     * @return HTTP 200 OK with Page of StockLogResponse
     */
    @GetMapping
    @Operation(summary = "Get stock logs", description = "Retrieve stock logs with optional filtering by barcodeId and date range")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Stock logs retrieved successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<Page<StockLogResponse>>> getStockLogs(
            @RequestParam(required = false) String barcodeId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<StockLogResponse> response = stockLogService.getStockLogs(barcodeId, startDate, endDate, pageable);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    /**
     * Get current stock for a product by barcodeId
     *
     * @param barcodeId Barcode ID
     * @return HTTP 200 OK with CurrentStockResponse
     */
    @GetMapping("/{barcodeId}")
    @Operation(summary = "Get current stock", description = "Retrieve current stock quantity for a product")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Current stock retrieved successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Stock not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<CurrentStockResponse>> getCurrentStock(
            @PathVariable(name = "barcodeId") String barcodeId) {
        CurrentStockResponse response = stockLogService.getCurrentStock(barcodeId);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }
}
