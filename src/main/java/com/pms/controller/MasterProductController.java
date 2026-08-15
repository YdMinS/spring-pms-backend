package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.MasterOptionRequest;
import com.pms.dto.request.MasterProductRequest;
import com.pms.dto.request.MasterProductUpdateRequest;
import com.pms.dto.response.ListingMatrixResponse;
import com.pms.dto.response.MasterOptionResponse;
import com.pms.dto.response.MasterProductResponse;
import com.pms.service.MasterProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Master product definition (CRUD + options) + channel coverage matrix (FEATURE_2608_06 / 3a, 3b-1).
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

    @PostMapping
    @Operation(summary = "Create master product")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductResponse>> createMasterProduct(
            @Valid @RequestBody MasterProductRequest request) {
        MasterProductResponse response = masterProductService.createMasterProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDTO.success(response));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update master product content (name/detailSource/fieldValues/active/components)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterProductResponse>> updateMasterProduct(
            @PathVariable Long id, @Valid @RequestBody MasterProductUpdateRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.updateMasterProduct(id, request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete master product (active=false)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> deleteMasterProduct(@PathVariable Long id) {
        masterProductService.deleteMasterProduct(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    @PostMapping("/{id}/options")
    @Operation(summary = "Create master option (validates component coverage)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterOptionResponse>> createOption(
            @PathVariable Long id, @Valid @RequestBody MasterOptionRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.createOption(id, request)));
    }

    @PatchMapping("/{id}/options/{optionId}")
    @Operation(summary = "Update master option (items replace whole set + re-validate)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MasterOptionResponse>> updateOption(
            @PathVariable Long id, @PathVariable Long optionId, @Valid @RequestBody MasterOptionRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(masterProductService.updateOption(id, optionId, request)));
    }

    @DeleteMapping("/{id}/options/{optionId}")
    @Operation(summary = "Delete master option")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> deleteOption(
            @PathVariable Long id, @PathVariable Long optionId) {
        masterProductService.deleteOption(id, optionId);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
