package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.PushSyncRequest;
import com.pms.dto.response.PendingSyncResponse;
import com.pms.dto.response.PropagateResponse;
import com.pms.dto.response.PushSyncResponse;
import com.pms.service.listing.ListingPropagationService;
import com.pms.service.listing.MasterPropagationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Two-layer propagation endpoints (FEATURE_2608_06 / 3d). ADMIN-only via the global {@code /api/admin/**} rule
 * (SecurityConfig) — no per-method {@code @PreAuthorize}. Cells/masters are tenant-scoped in the services
 * (cross-tenant/absent id → 404 for propagate, skip for push).
 *
 * <p>Layer A ({@code propagate}) is also auto-triggered on a master content update; this endpoint is the manual
 * entry point for price-input changes (Product.price / MarginPolicy / CarrierRate / Package) that do not touch
 * the master. Layer B ({@code pending-sync} / {@code push-sync}) is the gated, multi-select market push.</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Listing Propagation", description = "Propagate master changes to cells + push to market (ADMIN only)")
public class ListingPropagationController {

    private final MasterPropagationService masterPropagationService;
    private final ListingPropagationService listingPropagationService;

    @PostMapping("/master-products/{id}/propagate")
    @Operation(summary = "Layer A: re-generate linked cells' assets locally (manual, for price-input changes)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<PropagateResponse>> propagate(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(masterPropagationService.propagate(id)));
    }

    @GetMapping("/listings/pending-sync")
    @Operation(summary = "Layer B: list cells regenerated locally but not yet pushed to the market")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<PendingSyncResponse>>> pendingSync() {
        return ResponseEntity.ok(ResponseDTO.success(listingPropagationService.pendingSync()));
    }

    @PostMapping("/listings/push-sync")
    @Operation(summary = "Layer B: push a multi-select batch of pending cells to the market (→ SUBMITTED)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<PushSyncResponse>> pushSync(@Valid @RequestBody PushSyncRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(listingPropagationService.pushSync(request.getListingIds())));
    }
}
