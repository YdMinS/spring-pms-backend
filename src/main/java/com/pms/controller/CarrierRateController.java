package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.CarrierRateRequest;
import com.pms.dto.response.CarrierRateResponse;
import com.pms.service.CarrierRateService;
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
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/carrier-rate")
@RequiredArgsConstructor
@Tag(name = "Carrier Rate", description = "Carrier rate management API (ADMIN only)")
public class CarrierRateController {

    private final CarrierRateService carrierRateService;

    @PostMapping
    @Operation(summary = "Create carrier rate", description = "Create a new carrier rate (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "201", description = "Carrier rate created successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<CarrierRateResponse>> createCarrierRate(
            @Valid @RequestBody CarrierRateRequest request) {
        CarrierRateResponse response = carrierRateService.createCarrierRate(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDTO.success(response));
    }

    @GetMapping
    @Operation(summary = "List carrier rates", description = "Get all carrier rates (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Carrier rates retrieved successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<List<CarrierRateResponse>>> getCarrierRates() {
        List<CarrierRateResponse> response = carrierRateService.getCarrierRates();
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get carrier rate", description = "Get carrier rate by ID (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Carrier rate found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Carrier rate not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<CarrierRateResponse>> getCarrierRate(
            @PathVariable
            @Parameter(description = "Carrier rate ID")
            Long id) {
        CarrierRateResponse response = carrierRateService.getCarrierRate(id);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update carrier rate", description = "Update carrier rate (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Carrier rate updated successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Carrier rate not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<CarrierRateResponse>> updateCarrierRate(
            @PathVariable
            @Parameter(description = "Carrier rate ID")
            Long id,
            @Valid @RequestBody CarrierRateRequest request) {
        CarrierRateResponse response = carrierRateService.updateCarrierRate(id, request);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete carrier rate", description = "Delete carrier rate permanently (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Carrier rate deleted successfully",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Carrier rate not found",
            content = @Content(schema = @Schema(implementation = ResponseDTO.class)))
    public ResponseEntity<ResponseDTO<Void>> deleteCarrierRate(
            @PathVariable
            @Parameter(description = "Carrier rate ID")
            Long id) {
        carrierRateService.deleteCarrierRate(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
