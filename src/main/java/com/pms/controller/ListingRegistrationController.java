package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.ListingRegisterResponse;
import com.pms.dto.response.ListingStatusResponse;
import com.pms.dto.response.ListingSyncResponse;
import com.pms.service.listing.ListingRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Channel registration endpoints (FEATURE_2608_06 / 3c). ADMIN-only via the global {@code POST /api/admin/**}
 * rule (SecurityConfig) — no per-method {@code @PreAuthorize}. The cell is tenant-scoped in the service
 * (cross-tenant/absent id → 404).
 *
 * <p>Register returns SUBMITTED immediately (no approval wait); approval is detected by a manual fetch-status
 * refresh or the sync-approvals sweep.</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Listing Registration", description = "Push DRAFT cells to the market + sync approvals (ADMIN only)")
public class ListingRegistrationController {

    private final ListingRegistrationService listingRegistrationService;

    @PostMapping("/product-listings/{id}/register")
    @Operation(summary = "Push a DRAFT cell to the market → SUBMITTED (no approval wait)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingRegisterResponse>> register(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(listingRegistrationService.register(id)));
    }

    @PostMapping("/product-listings/{id}/update-request")
    @Operation(summary = "[수정 요청] Re-submit an already-registered cell for review → SUBMITTED")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingRegisterResponse>> updateRequest(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(listingRegistrationService.updateRequest(id)));
    }

    @PostMapping("/product-listings/{id}/fetch-status")
    @Operation(summary = "Manual refresh: fetch market status + sync option ids/approval")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingStatusResponse>> fetchStatus(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(listingRegistrationService.fetchStatus(id)));
    }

    @PostMapping("/listings/sync-approvals")
    @Operation(summary = "Sweep pending (SUBMITTED + not-approved) listings; per-listing failures isolated")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingSyncResponse>> syncApprovals() {
        return ResponseEntity.ok(ResponseDTO.success(listingRegistrationService.syncApprovals()));
    }
}
