package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.GeneratedProductResponse;
import com.pms.service.ListingAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Channel-cell auto-generated assets (FEATURE_2608_06 / 3b-2). ADMIN-only via the global
 * {@code /api/admin/**} rule (SecurityConfig) — no per-method {@code @PreAuthorize}. The cell is
 * tenant-scoped in the service (a cross-tenant/absent id → 404).
 */
@RestController
@RequestMapping("/api/admin/product-listings")
@RequiredArgsConstructor
@Tag(name = "Listing Assets", description = "Channel-cell thumbnail/detail/price generation (ADMIN only)")
public class ListingAssetController {

    private final ListingAssetService listingAssetService;

    @PostMapping("/{id}/regenerate")
    @Operation(summary = "Regenerate a cell's thumbnail + detail + option prices (backfill consumer)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<GeneratedProductResponse>> regenerate(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(listingAssetService.regenerate(id)));
    }

    @GetMapping("/{id}/generated")
    @Operation(summary = "Read a cell's generated assets (404 if not yet generated)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<GeneratedProductResponse>> getGenerated(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(listingAssetService.getGenerated(id)));
    }
}
