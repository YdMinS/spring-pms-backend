package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.MarginPreviewRequest;
import com.pms.dto.response.ListingMarginResponse;
import com.pms.service.price.ListingMarginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Margin view of a listing + the [가격 설정] margin preview (FEATURE_2610_27 / D1 · D6 · D9 · D10 · D20). ADMIN-only via
 * the global {@code /api/admin/**} rules (SecurityConfig) — no per-method {@code @PreAuthorize}. The listing is
 * tenant-scoped in the service (cross-tenant/absent id → 404). No market call, nothing stored.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Listing Margin", description = "Per-option margin of a listing and price-input margin preview (ADMIN only)")
public class ListingMarginController {

    private final ListingMarginService listingMarginService;

    @GetMapping("/product-listings/{id}/margins")
    @Operation(summary = "Every option's margin at the market price (system price when unknown)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingMarginResponse>> margins(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(listingMarginService.margins(id)));
    }

    @PostMapping("/product-listings/{id}/options/{optionId}/margin-preview")
    @Operation(summary = "Margin of a price typed in the price window (nothing stored or sent)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingMarginResponse.SiteMargin>> preview(
            @PathVariable Long id, @PathVariable Long optionId, @Valid @RequestBody MarginPreviewRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(
                listingMarginService.preview(id, optionId, request.getPrice(), request.isAuction())));
    }
}
