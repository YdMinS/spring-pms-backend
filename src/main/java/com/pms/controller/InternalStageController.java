package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.OrderAcknowledgeRequest;
import com.pms.service.reservation.InternalShipmentStageService;
import com.pms.service.reservation.InternalStageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 발주 표시 달기·떼기 (FEATURE_2609_75 / D1 · D18). ADMIN 전용 — 쿠팡에는 보내지 않는다.
 * 요청 DTO 는 발주처리와 같은 {@link OrderAcknowledgeRequest}(라인 id 1~500)를 쓴다.
 */
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Internal Order Stage", description = "Internal acknowledgement marker (ADMIN only)")
public class InternalStageController {

    private final InternalShipmentStageService internalShipmentStageService;

    @PostMapping("/internal-acknowledge")
    @Operation(summary = "Mark orders as internally acknowledged",
            description = "PAID Coupang boxes of the selected lines → internal_stage INTERNAL_PREPARING (ADMIN only)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Changed/skipped/unsupported")
    @ApiResponse(responseCode = "400", description = "Empty selection or no order line found")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<InternalStageResult>> markInternal(
            @Valid @RequestBody OrderAcknowledgeRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(internalShipmentStageService.markInternal(request.orderItemIds())));
    }

    @PostMapping("/internal-acknowledge/release")
    @Operation(summary = "Release internal acknowledgement",
            description = "INTERNAL_PREPARING boxes of the selected lines → no stage (ADMIN only)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Changed/skipped/unsupported")
    @ApiResponse(responseCode = "400", description = "Empty selection or no order line found")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<InternalStageResult>> releaseInternal(
            @Valid @RequestBody OrderAcknowledgeRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(internalShipmentStageService.releaseInternal(request.orderItemIds())));
    }
}
