package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.BatchChannelAddRequest;
import com.pms.dto.request.ChannelAddRequest;
import com.pms.dto.response.BatchChannelAddResponse;
import com.pms.dto.response.ChannelAddResponse;
import com.pms.service.ChannelAddService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Channel add (FEATURE_2608_06 / 3b'): create a DRAFT channel-cell listing under a master product.
 *
 * <p>ADMIN-only via the global {@code POST /api/admin/**} rule (SecurityConfig) — no per-method
 * {@code @PreAuthorize}. Tenant scoping + duplicate/validation are enforced in the service (a cross-tenant
 * or absent master → 404, an already-registered account → 409).</p>
 */
@RestController
@RequestMapping("/api/admin/master-products")
@RequiredArgsConstructor
@Tag(name = "Channel Add", description = "Add a channel cell (DRAFT listing) for a master product (ADMIN only)")
public class ChannelAddController {

    private final ChannelAddService channelAddService;

    @PostMapping("/{masterProductId}/listings")
    @Operation(summary = "Add a channel: copy master options → new DRAFT listing + generate assets")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ChannelAddResponse>> addChannel(
            @PathVariable Long masterProductId, @Valid @RequestBody ChannelAddRequest request) {
        ChannelAddResponse response = channelAddService.addChannel(masterProductId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDTO.success(response));
    }

    @PostMapping("/{masterProductId}/listings/batch")
    @Operation(summary = "Add multiple channels at once (per-target isolation; partial failure allowed)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<BatchChannelAddResponse>> addChannelsBatch(
            @PathVariable Long masterProductId, @Valid @RequestBody BatchChannelAddRequest request) {
        // Always 200 — partial failure is reported in the body (succeeded/failed counts), not as an error status.
        BatchChannelAddResponse response = channelAddService.addChannelsBatch(masterProductId, request);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }
}
