package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.service.ShipmentConfirmResult;
import com.pms.service.ShipmentConfirmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 발송처리 컨트롤러 (ADMIN 전용, 발송처리 레그).
 *
 * 택배사 결과 xlsx 를 업로드하면 {@link ShipmentConfirmService#confirm} 로 order_item 을 전개해
 * 계정별 쿠팡 송장업로드 배치를 전송하고 결과(JSON)를 반환한다.
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
}
