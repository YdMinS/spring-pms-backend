package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.OrderSettingRequest;
import com.pms.dto.response.OrderSettingResponse;
import com.pms.service.reservation.OrderSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 주문관리 설정 — 기본 예약 발송 시각 (FEATURE_2609_75 / D4 · D12). ADMIN 전용. */
@RestController
@RequestMapping("/api/admin/order-settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Order Settings", description = "Tenant order settings (ADMIN only)")
public class OrderSettingController {

    private final OrderSettingService orderSettingService;

    @GetMapping
    @Operation(summary = "Get order settings", description = "Default reserved shipment time (KST) + next occurrence")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Settings")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<OrderSettingResponse>> get() {
        return ResponseEntity.ok(ResponseDTO.success(orderSettingService.get()));
    }

    @PutMapping
    @Operation(summary = "Update order settings", description = "reservedShipmentTime must be HH:mm (KST)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Saved settings")
    @ApiResponse(responseCode = "400", description = "Blank or malformed time")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<OrderSettingResponse>> update(@Valid @RequestBody OrderSettingRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(orderSettingService.update(request)));
    }
}
