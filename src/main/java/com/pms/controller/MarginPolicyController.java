package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.MarginPolicyRequest;
import com.pms.dto.response.MarginPolicyResponse;
import com.pms.service.MarginPolicyService;
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
 * Margin preset CRUD (FEATURE_2608_06 / 3a). ADMIN-only via the global {@code /api/admin/**} rule.
 */
@RestController
@RequestMapping("/api/admin/margin-policies")
@RequiredArgsConstructor
@Tag(name = "Margin Policy", description = "Margin preset management API (ADMIN only)")
public class MarginPolicyController {

    private final MarginPolicyService marginPolicyService;

    @PostMapping
    @Operation(summary = "Create margin preset")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MarginPolicyResponse>> createMarginPolicy(
            @Valid @RequestBody MarginPolicyRequest request) {
        MarginPolicyResponse response = marginPolicyService.createMarginPolicy(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDTO.success(response));
    }

    @GetMapping
    @Operation(summary = "List margin presets")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<MarginPolicyResponse>>> getMarginPolicies() {
        return ResponseEntity.ok(ResponseDTO.success(marginPolicyService.getMarginPolicies()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get margin preset by ID")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MarginPolicyResponse>> getMarginPolicy(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(marginPolicyService.getMarginPolicy(id)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update margin preset")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<MarginPolicyResponse>> updateMarginPolicy(
            @PathVariable Long id, @Valid @RequestBody MarginPolicyRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(marginPolicyService.updateMarginPolicy(id, request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete margin preset")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> deleteMarginPolicy(@PathVariable Long id) {
        marginPolicyService.deleteMarginPolicy(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
