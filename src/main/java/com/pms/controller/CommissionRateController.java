package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.CommissionRateRequest;
import com.pms.dto.response.CommissionRateResponse;
import com.pms.service.CommissionRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for Commission Rate management.
 *
 * All endpoints require ADMIN role authentication.
 * Base path: /api/admin/commission-rate
 *
 * Fallback logic: When querying rates via service.findRate(),
 * system first checks category-specific rate, then falls back to platform default.
 *
 * @see CommissionRateService
 * @see CommissionRateRequest
 * @see CommissionRateResponse
 */
@RestController
@RequestMapping("/api/admin/commission-rate")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Commission Rate", description = "Commission rate management API (ADMIN only)")
public class CommissionRateController {

    private final CommissionRateService commissionRateService;

    @PostMapping
    @Operation(summary = "Create commission rate", description = "Create a new commission rate (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "201", description = "Commission rate created successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<CommissionRateResponse>> create(
            @Valid @RequestBody CommissionRateRequest request) {
        CommissionRateResponse response = commissionRateService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDTO.success(response));
    }

    @GetMapping
    @Operation(summary = "List commission rates", description = "Get all commission rates (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Commission rates retrieved successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<List<CommissionRateResponse>>> findAll() {
        List<CommissionRateResponse> response = commissionRateService.findAll();
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get commission rate", description = "Get commission rate by ID (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Commission rate found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Commission rate not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<CommissionRateResponse>> findById(
            @PathVariable
            @Parameter(description = "Commission rate ID")
            Long id) {
        CommissionRateResponse response = commissionRateService.findById(id);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update commission rate", description = "Update commission rate (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Commission rate updated successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Commission rate not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<CommissionRateResponse>> update(
            @PathVariable
            @Parameter(description = "Commission rate ID")
            Long id,
            @Valid @RequestBody CommissionRateRequest request) {
        CommissionRateResponse response = commissionRateService.update(id, request);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete commission rate", description = "Delete commission rate permanently (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Commission rate deleted successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Commission rate not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<Void>> delete(
            @PathVariable
            @Parameter(description = "Commission rate ID")
            Long id) {
        commissionRateService.delete(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
