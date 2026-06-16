package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.MarketplaceAccountRequest;
import com.pms.dto.response.MarketplaceAccountResponse;
import com.pms.service.MarketplaceAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 외부 플랫폼 계정/자격증명 관리 API (ADMIN 전용).
 *
 * 모든 엔드포인트는 ADMIN 권한 필요 (SecurityConfig /api/admin/** + 클래스 @PreAuthorize).
 * secretKey 는 입력 전용이며 응답에는 노출되지 않는다.
 */
@RestController
@RequestMapping("/api/admin/marketplace-account")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Marketplace Account", description = "Marketplace account/credential management API (ADMIN only)")
public class MarketplaceAccountController {

    private final MarketplaceAccountService service;

    @PostMapping
    @Operation(summary = "Create marketplace account", description = "Register a platform account + credentials (ADMIN only)")
    public ResponseEntity<ResponseDTO<MarketplaceAccountResponse>> create(
            @Valid @RequestBody MarketplaceAccountRequest request) {
        MarketplaceAccountResponse response = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDTO.success(response));
    }

    @GetMapping
    @Operation(summary = "List marketplace accounts",
            description = "Get marketplace accounts; filter by sellerId when provided (ADMIN only)")
    public ResponseEntity<ResponseDTO<List<MarketplaceAccountResponse>>> list(
            @RequestParam(required = false)
            @Parameter(description = "Filter by owning seller ID (optional)") Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(service.list(sellerId)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get marketplace account", description = "Get marketplace account by ID (ADMIN only)")
    public ResponseEntity<ResponseDTO<MarketplaceAccountResponse>> get(
            @PathVariable @Parameter(description = "Marketplace account ID") Long id) {
        return ResponseEntity.ok(ResponseDTO.success(service.get(id)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update marketplace account", description = "Update account; blank secretKey keeps existing (ADMIN only)")
    public ResponseEntity<ResponseDTO<MarketplaceAccountResponse>> update(
            @PathVariable @Parameter(description = "Marketplace account ID") Long id,
            @Valid @RequestBody MarketplaceAccountRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(service.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete marketplace account", description = "Delete marketplace account permanently (ADMIN only)")
    public ResponseEntity<ResponseDTO<Void>> delete(
            @PathVariable @Parameter(description = "Marketplace account ID") Long id) {
        service.delete(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
