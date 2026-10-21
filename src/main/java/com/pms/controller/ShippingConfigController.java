package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ElevenstShippingConfigRequest;
import com.pms.dto.request.EsmShippingConfigRequest;
import com.pms.dto.request.ShippingConfigRequest;
import com.pms.dto.response.ElevenstShippingConfigResponse;
import com.pms.dto.response.EsmChoiceResponse;
import com.pms.dto.response.EsmShippingConfigResponse;
import com.pms.dto.response.OutboundPlaceResponse;
import com.pms.dto.response.ReturnCenterResponse;
import com.pms.dto.response.ShippingConfigResponse;
import com.pms.service.ShippingConfigService;
import com.pms.service.listing.shipping.ElevenstShippingRepricer;
import com.pms.service.listing.shipping.EsmShippingRepricer;
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
 * return centers + the per-account shipping config (+ the ESM choice lookups, FEATURE_2610_26 / D37). All under {@code /api/admin/marketplace-account/{id}} so the
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
    private final ElevenstShippingRepricer elevenstShippingRepricer;
    private final EsmShippingRepricer esmShippingRepricer;

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

    @GetMapping("/elevenst-shipping-config")
    @Operation(summary = "Get the 11st shipping settings of an 11st sales channel (FEATURE_2610_13; empty map = unset)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ElevenstShippingConfigResponse>> getElevenstConfig(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(shippingConfigService.getElevenstConfig(id)));
    }

    @PutMapping("/elevenst-shipping-config")
    @Operation(summary = "Replace the 11st shipping settings of an 11st sales channel (FEATURE_2610_13)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ElevenstShippingConfigResponse>> upsertElevenstConfig(
            @PathVariable Long id,
            @Valid @RequestBody ElevenstShippingConfigRequest request) {
        ElevenstShippingConfigResponse saved = shippingConfigService.upsertElevenstConfig(id, request.getSettings());
        // D117 · D123: re-price this channel's 11st cells AFTER the save has committed, one transaction per cell.
        // A failed cell keeps its old prices and is only counted (D120) — the saved settings stay.
        int failed = elevenstShippingRepricer.repriceAccount(id);
        return ResponseEntity.ok(ResponseDTO.success(saved.toBuilder().priceRecalcFailedCount(failed).build()));
    }

    @GetMapping("/esm-shipping-config")
    @Operation(summary = "Get the ESM shipping settings of an ESM sales channel (FEATURE_2610_26; empty map = unset)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<EsmShippingConfigResponse>> getEsmConfig(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(shippingConfigService.getEsmConfig(id)));
    }

    @PutMapping("/esm-shipping-config")
    @Operation(summary = "Replace the ESM shipping settings of an ESM sales channel (FEATURE_2610_26)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<EsmShippingConfigResponse>> upsertEsmConfig(
            @PathVariable Long id,
            @Valid @RequestBody EsmShippingConfigRequest request) {
        EsmShippingConfigResponse saved = shippingConfigService.upsertEsmConfig(id, request.getSettings());
        // D65: re-price this channel's ESM cells AFTER the save has committed, one transaction per cell. A failed
        // cell keeps its old prices and is only counted — the saved settings stay.
        int failed = esmShippingRepricer.repriceAccount(id);
        return ResponseEntity.ok(ResponseDTO.success(saved.toBuilder().priceRecalcFailedCount(failed).build()));
    }

    @GetMapping("/esm-shipping/bundle-policies")
    @Operation(summary = "List the bundle-fee policies of one ESM shipping place (FEATURE_2610_26 / D37)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<EsmChoiceResponse>>> esmBundlePolicies(
            @PathVariable Long id, @RequestParam("placeNo") String placeNo) {
        return ResponseEntity.ok(ResponseDTO.success(shippingConfigService.listEsmBundlePolicies(id, placeNo)));
    }

    @GetMapping("/esm-shipping/dispatch-policies")
    @Operation(summary = "List the ESM dispatch policies (FEATURE_2610_26 / D37 · D68)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<EsmChoiceResponse>>> esmDispatchPolicies(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(shippingConfigService.listEsmDispatchPolicies(id)));
    }

    @GetMapping("/esm-shipping/delivery-companies")
    @Operation(summary = "List the couriers ESM accepts (FEATURE_2610_26 / D37)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<EsmChoiceResponse>>> esmDeliveryCompanies(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(shippingConfigService.listEsmDeliveryCompanies(id)));
    }
}
