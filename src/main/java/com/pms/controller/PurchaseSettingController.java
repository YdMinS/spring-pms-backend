package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.PurchaseSettingRequest;
import com.pms.dto.response.PurchaseSettingResponse;
import com.pms.service.PurchaseSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Purchase settings — the stock link switch (FEATURE_2610_09 / D65 · D72).
 *
 * <p>🔴 Authority lives in {@code SecurityConfig}, not here: {@code GET /api/admin/purchase-settings} is open to
 * every signed-in user (web and mobile menus read it, D71 ②), while PUT falls under the {@code /api/admin/**}
 * ADMIN rule — the same split as {@code /api/admin/purchase-places}.</p>
 */
@RestController
@RequestMapping("/api/admin/purchase-settings")
@RequiredArgsConstructor
@Tag(name = "Purchase Settings", description = "Tenant purchase settings (read: any user, write: ADMIN)")
public class PurchaseSettingController {

    private final PurchaseSettingService purchaseSettingService;

    @GetMapping
    @Operation(summary = "Get purchase settings", description = "stockLinked — false when the tenant has no row")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Settings")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    public ResponseEntity<ResponseDTO<PurchaseSettingResponse>> get() {
        return ResponseEntity.ok(ResponseDTO.success(purchaseSettingService.get()));
    }

    @PutMapping
    @Operation(summary = "Update purchase settings (ADMIN)", description = "stockLinked is required")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Saved settings")
    @ApiResponse(responseCode = "400", description = "stockLinked missing")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<PurchaseSettingResponse>> update(
            @Valid @RequestBody PurchaseSettingRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(purchaseSettingService.update(request)));
    }
}
