package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ShippingLabelExportRequest;
import com.pms.dto.response.ShippingLabelPreviewRow;
import com.pms.service.ShippingLabelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 송장 접수용 스프레드시트 컨트롤러 (ADMIN 전용, 생성 레그).
 *
 * preview(목록/단건, JSON) 로 편집용 행을 내려주고, 편집된 행을 받아 xlsx 로 변환한다.
 * 남는 경로 3개: GET /v2/preview · GET /v2/preview/by-order · POST /v2/spreadsheet.
 * 편집 없이 즉시 xlsx 를 주던 V1(GET /spreadsheet)은 2026-09-02 제거됨(FEATURE_2609_04).
 */
@RestController
@RequestMapping("/api/admin/shipping-labels")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Shipping Label", description = "Shipping label spreadsheet API (ADMIN only)")
public class ShippingLabelController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ShippingLabelService shippingLabelService;

    @GetMapping("/v2/preview")
    @Operation(summary = "Preview editable shipping label rows",
            description = "Coupang INSTRUCT orders → editable rows JSON (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Editable rows")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    @ApiResponse(responseCode = "500", description = "Coupang ordersheets fetch/parse failed")
    public ResponseEntity<ResponseDTO<List<ShippingLabelPreviewRow>>> preview(
            @RequestParam(required = false)
            @Parameter(description = "Seller ID filter (optional; all active accounts if omitted)")
            Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(shippingLabelService.previewRows(sellerId)));
    }

    @GetMapping("/v2/preview/by-order")
    @Operation(summary = "Preview shipping label rows for a single order",
            description = "Coupang single-order lookup (any status) → editable rows JSON (ADMIN only)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Editable rows")
    @ApiResponse(responseCode = "400", description = "Not a Coupang order")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    @ApiResponse(responseCode = "404", description = "Order not found")
    @ApiResponse(responseCode = "500", description = "Coupang ordersheet fetch/parse failed")
    public ResponseEntity<ResponseDTO<List<ShippingLabelPreviewRow>>> previewByOrder(
            @RequestParam
            @Parameter(description = "order_item PK (not the Coupang orderId)")
            Long orderItemId) {
        return ResponseEntity.ok(ResponseDTO.success(shippingLabelService.previewRowsByOrder(orderItemId)));
    }

    @PostMapping("/v2/spreadsheet")
    @Operation(summary = "Export edited shipping label spreadsheet",
            description = "Edited rows → carrier receipt xlsx (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "xlsx file")
    @ApiResponse(responseCode = "400", description = "Validation failed (e.g. parcelQuantity < 1)")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<byte[]> exportSpreadsheet(@Valid @RequestBody ShippingLabelExportRequest req) {
        byte[] xlsx = shippingLabelService.toXlsxFromExport(req.rows());

        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"shipping-labels.xlsx\"")
                .body(xlsx);
    }
}
