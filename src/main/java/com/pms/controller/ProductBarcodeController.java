package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.BarcodeExtractionRequest;
import com.pms.dto.response.BarcodeExtractionResult;
import com.pms.service.barcode.BarcodeExtractionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 물품 사진에서 바코드를 추출하는 창구 (FEATURE_2609_65 / PLAN D1).
 *
 * <p>ADMIN 전용이며 권한은 전역 {@code POST /api/admin/**} → {@code hasRole("ADMIN")}(SecurityConfig)에
 * 맡긴다 — {@code ProductImageController} 와 같은 관례로, 메서드 {@code @PreAuthorize} 를 달지 않는다.</p>
 *
 * <p>🔴 엔드포인트 <b>하나</b>로 일괄과 개별을 다 받는다 — 상세 화면도 id 1개짜리 배열을 보낸다.
 * 단건용 경로({@code /{id}/barcode})를 더하면 중복 판정·덮어쓰기 규칙이 두 곳으로 갈라진다.
 * 경로가 리터럴 1단이라 {@code /api/admin/products/{productId}/images} 와도 겹치지 않는다.</p>
 */
@RestController
@RequestMapping("/api/admin/products/barcode-extraction")
@RequiredArgsConstructor
@Tag(name = "Product Barcode", description = "물품 사진에서 바코드 추출 (ADMIN only)")
public class ProductBarcodeController {

    private final BarcodeExtractionService barcodeExtractionService;

    @PostMapping
    @Operation(summary = "Extract barcodes from the products' images (no marketplace/LLM call)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<BarcodeExtractionResult>> extract(
            @Valid @RequestBody BarcodeExtractionRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(barcodeExtractionService.extract(request)));
    }
}
