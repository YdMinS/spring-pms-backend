package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.ListingMatrixResponse;
import com.pms.dto.response.MasterProductResponse;
import com.pms.service.MasterProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Master product reads + channel coverage matrix (FEATURE_2608_06 / 3a).
 *
 * <p>All endpoints are ADMIN-only via the global {@code /api/admin/**} rule (SecurityConfig) — no
 * per-method {@code @PreAuthorize}. Tenant scoping is enforced in the service (tenant-filtered reads).</p>
 */
@RestController
@RequestMapping("/api/admin/master-products")
@RequiredArgsConstructor
@Tag(name = "Master Product", description = "Master product + coverage matrix API (ADMIN only)")
public class MasterProductController {

    private final MasterProductService masterProductService;

    @GetMapping
    @Operation(summary = "List master products")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<MasterProductResponse>>> getMasterProducts() {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.getMasterProducts()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get master product by ID")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductResponse>> getMasterProduct(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.getMasterProduct(id)));
    }

    @GetMapping("/{id}/matrix")
    @Operation(summary = "Get channel coverage matrix for a master product")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingMatrixResponse>> getMatrix(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.getMatrix(id)));
    }
}
