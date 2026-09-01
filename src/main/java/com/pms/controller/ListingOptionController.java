package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.SetActiveOptionsRequest;
import com.pms.dto.request.SetOptionStocksRequest;
import com.pms.dto.response.ListingOptionsResponse;
import com.pms.service.listing.ListingOptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Per-channel option selection endpoints (FEATURE_2608_06 / 42). ADMIN-only via the global {@code /api/admin/**}
 * rule (SecurityConfig) — no per-method {@code @PreAuthorize}. The listing is tenant-scoped in the service
 * (cross-tenant/absent id → 404).
 *
 * <p>The active subset controls which options the market register/update payload pushes; register/fetch-status/
 * sync-approvals (3c) are unchanged (they only gained an internal active filter).</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Listing Options", description = "Per-channel active-option selection (ADMIN only)")
public class ListingOptionController {

    private final ListingOptionService listingOptionService;

    @GetMapping("/product-listings/{id}/options")
    @Operation(summary = "Full option set (active + inactive) for a channel listing")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingOptionsResponse>> getOptions(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(listingOptionService.getOptions(id)));
    }

    @PutMapping("/product-listings/{id}/options/active")
    @Operation(summary = "Set the whole active-option set (bulk); returns needsResync if the cell is already pushed")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingOptionsResponse>> setActiveOptions(
            @PathVariable Long id, @Valid @RequestBody SetActiveOptionsRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                listingOptionService.setActiveOptions(id, request.getActiveOptionIds())));
    }

    @PutMapping("/product-listings/{id}/options/stock")
    @Operation(summary = "Set the stock quantity of some options (partial; null clears the override)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingOptionsResponse>> setOptionStocks(
            @PathVariable Long id, @Valid @RequestBody SetOptionStocksRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                listingOptionService.setOptionStocks(id, request.getStocks())));
    }
}
