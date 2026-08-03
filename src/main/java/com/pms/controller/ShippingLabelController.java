package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ShippingLabelExportRequest;
import com.pms.dto.response.ShippingLabelPreviewRow;
import com.pms.service.ShippingLabelRow;
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
 * 송장 접수용 스프레드시트 다운로드 컨트롤러 (ADMIN 전용, 생성 레그).
 *
 * {@link ShippingLabelService#collectRows} → {@link ShippingLabelService#toXlsx} 순으로
 * 오케스트레이션한다(별도 파사드 없음). 결과는 xlsx bytes 다운로드.
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

    @GetMapping("/spreadsheet")
    @Operation(summary = "Download shipping label spreadsheet",
            description = "Coupang INSTRUCT orders → carrier receipt xlsx (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "xlsx file")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    @ApiResponse(responseCode = "500", description = "Coupang ordersheets fetch/parse failed")
    public ResponseEntity<byte[]> downloadSpreadsheet(
            @RequestParam(required = false)
            @Parameter(description = "Seller ID filter (optional; all active accounts if omitted)")
            Long sellerId) {
        List<ShippingLabelRow> rows = shippingLabelService.collectRows(sellerId);
        byte[] xlsx = shippingLabelService.toXlsx(rows);

        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"shipping-labels.xlsx\"")
                .body(xlsx);
    }

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
