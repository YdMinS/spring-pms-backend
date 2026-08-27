package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ShippingConfigRequest;
import com.pms.dto.response.OutboundPlaceResponse;
import com.pms.dto.response.ReturnCenterResponse;
import com.pms.dto.response.ShippingConfigResponse;
import com.pms.service.ShippingConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Shipping management endpoints for a marketplace account (FEATURE_2608_06 / 72): lookup-first outbound places /
 * return centers + the per-account shipping config. All under {@code /api/admin/marketplace-account/{id}} so the
 * global {@code /api/admin/**} ADMIN rule (SecurityConfig; PUT added in 015) applies — no per-method
 * {@code @PreAuthorize}. Kept separate from {@code MarketplaceAccountController} (which carries a class-level
 * {@code @PreAuthorize}) to lean on the global rule, like {@code CategoryLookupController}.
 */
@RestController
@RequestMapping("/api/admin/marketplace-account/{id}")
@RequiredArgsConstructor
@Tag(name = "Shipping Config", description = "Outbound/return lookup + shipping settings per account (ADMIN only)")
public class ShippingConfigController {

    private final ShippingConfigService shippingConfigService;

    @GetMapping("/shipping-places/outbound")
    @Operation(summary = "List outbound shipping places (platform lookup; empty = manual entry)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<OutboundPlaceResponse>>> outbound(@PathVariable Long id) {
        List<OutboundPlaceResponse> places = shippingConfigService.listOutbound(id).stream()
                .map(OutboundPlaceResponse::from)
                .toList();
        return ResponseEntity.ok(ResponseDTO.success(places));
    }

    @GetMapping("/shipping-places/return")
    @Operation(summary = "List return centers (full address block; platform lookup; empty = manual entry)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<ReturnCenterResponse>>> returnCenters(@PathVariable Long id) {
        List<ReturnCenterResponse> centers = shippingConfigService.listReturn(id).stream()
                .map(ReturnCenterResponse::from)
                .toList();
        return ResponseEntity.ok(ResponseDTO.success(centers));
    }

    @GetMapping("/shipping-config")
    @Operation(summary = "Get the account's shipping config (unset = all-null fields)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ShippingConfigResponse>> getConfig(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(shippingConfigService.getConfig(id)));
    }

    @PutMapping("/shipping-config")
    @Operation(summary = "Upsert the account's shipping config")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ShippingConfigResponse>> upsertConfig(
            @PathVariable Long id,
            @Valid @RequestBody ShippingConfigRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(shippingConfigService.upsertConfig(id, request)));
    }
}
