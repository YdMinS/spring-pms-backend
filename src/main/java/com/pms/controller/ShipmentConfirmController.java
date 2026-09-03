package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ManualShipmentRequest;
import com.pms.service.CarrierCodeService;
import com.pms.service.CarrierOption;
import com.pms.service.ManualShipmentResult;
import com.pms.service.ShipmentConfirmResult;
import com.pms.service.ShipmentConfirmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 발송처리 컨트롤러 (ADMIN 전용, 발송처리 레그).
 *
 * 택배사 결과 xlsx 를 업로드하면 {@link ShipmentConfirmService#confirm} 로 order_item 을 전개해
 * 계정별 쿠팡 송장업로드 배치를 전송하고 결과(JSON)를 반환한다.
 * 단건 수동 경로({@code /carrier-options} + {@code /confirm/manual}, PLAN 2609_11)도 여기 붙는다 —
 * 클래스 {@code @PreAuthorize} 가 그대로 적용되기 때문이다(D11).
 *
 * 생성 레그({@link ShippingLabelController})와 같은 경로 prefix 지만 컨트롤러는 분리한다
 * (다운로드=바이너리 GET, 발송처리=JSON POST). ADMIN 동일.
 */
@RestController
@RequestMapping("/api/admin/shipping-labels")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Shipment Confirm", description = "Shipment confirm (invoice upload) API (ADMIN only)")
public class ShipmentConfirmController {

    private final ShipmentConfirmService shipmentConfirmService;
    private final CarrierCodeService carrierCodeService;

    @PostMapping(value = "/confirm", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Confirm shipment (upload carrier result)",
            description = "Carrier result xlsx → order_item expand → Coupang invoice upload batch (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Confirm result (succeeded/unmatched/failed)")
    @ApiResponse(responseCode = "400", description = "Empty file or parse failure")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<ShipmentConfirmResult>> confirm(
            @RequestParam("file") MultipartFile file) {
        ShipmentConfirmResult result = shipmentConfirmService.confirm(file);
        return ResponseEntity.ok(ResponseDTO.success(result));
    }

    @GetMapping("/carrier-options")
    @Operation(summary = "List carrier options for manual shipment",
            description = "Active carriers that have a code for the given platform (ADMIN only)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Carrier options (empty list if none registered)")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<List<CarrierOption>>> carrierOptions(
            @RequestParam("platform") String platform) {
        return ResponseEntity.ok(ResponseDTO.success(carrierCodeService.findOptions(platform)));
    }

    @PostMapping("/confirm/manual")
    @Operation(summary = "Confirm one box (manual carrier + invoice number)",
            description = "Uploads (or updates) the invoice for the box of one order line (ADMIN only)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Manual confirm result (mode/succeeded/failed)")
    @ApiResponse(responseCode = "400", description = "Invalid request, non-Coupang order, or missing box id")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<ManualShipmentResult>> confirmManual(
            @Valid @RequestBody ManualShipmentRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(shipmentConfirmService.confirmManual(request)));
    }
}
