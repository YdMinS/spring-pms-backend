package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.BarcodeScanResponse;
import com.pms.service.barcode.BarcodeScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Reads a barcode from an uploaded image for product registration — returns the value only.
 *
 * <p>🔴 The image is never stored (no storage, no DB write). ADMIN only via the global
 * {@code POST /api/admin/**} → {@code hasRole("ADMIN")} rule in SecurityConfig — no method
 * {@code @PreAuthorize}, same convention as {@link ProductBarcodeController}.</p>
 *
 * <p>Sibling of {@link ProductBarcodeController} ({@code /api/admin/products/barcode-extraction}, which
 * reads stored product photos and writes {@code barcode_id}); this path is a separate literal segment.</p>
 */
@RestController
@RequestMapping("/api/admin/products/barcode-scan")
@RequiredArgsConstructor
@Tag(name = "Product Barcode", description = "물품 사진에서 바코드 추출 (ADMIN only)")
public class ProductBarcodeScanController {

    private final BarcodeScanService barcodeScanService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Read a barcode from an uploaded image (image is not stored; barcode/format null when none found)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<BarcodeScanResponse>> scan(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ResponseDTO.success(barcodeScanService.scan(file)));
    }
}
