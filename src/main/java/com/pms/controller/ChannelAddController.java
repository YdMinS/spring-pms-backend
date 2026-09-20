package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.BatchChannelAddRequest;
import com.pms.dto.request.ChannelAddRequest;
import com.pms.dto.request.ListingImportPreviewRequest;
import com.pms.dto.request.ListingImportRequest;
import com.pms.dto.response.BatchChannelAddResponse;
import com.pms.dto.response.ChannelAddResponse;
import com.pms.dto.response.ListingImportPreviewResponse;
import com.pms.service.ChannelAddService;
import com.pms.service.listing.ChannelLinkService;
import com.pms.service.listing.CoupangListingImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Channel add (FEATURE_2608_06 / 3b'): create a DRAFT channel-cell listing under a master product,
 * (FEATURE_2609_22) import a product that already exists on the marketplace as such a cell, and
 * (FEATURE_2609_63) detach a cell from its master / delete a never-registered DRAFT cell.
 *
 * <p>ADMIN-only via the global {@code POST /api/admin/**} rule (SecurityConfig) — no per-method
 * {@code @PreAuthorize}. Tenant scoping + duplicate/validation are enforced in the service (a cross-tenant
 * or absent master → 404, an already-registered account → 409).</p>
 */
@RestController
@RequestMapping("/api/admin/master-products")
@RequiredArgsConstructor
@Tag(name = "Channel Add",
        description = "Add, import, detach or delete a channel cell of a master product (ADMIN only)")
public class ChannelAddController {

    private final ChannelAddService channelAddService;
    private final CoupangListingImportService coupangListingImportService;
    private final ChannelLinkService channelLinkService;

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

    @PostMapping("/{masterProductId}/listings/import/preview")
    @Operation(summary = "Preview an existing marketplace product before importing it (read-only, no writes)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ListingImportPreviewResponse>> previewImport(
            @PathVariable Long masterProductId, @Valid @RequestBody ListingImportPreviewRequest request) {
        // 200, not 201 — nothing is created here (one marketplace GET, zero writes).
        ListingImportPreviewResponse response = coupangListingImportService.preview(masterProductId, request);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @PostMapping("/{masterProductId}/listings/import")
    @Operation(summary = "Import an existing marketplace product as a channel cell of this master")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ChannelAddResponse>> importListing(
            @PathVariable Long masterProductId, @Valid @RequestBody ListingImportRequest request) {
        ChannelAddResponse response = coupangListingImportService.importListing(masterProductId, request);
        return ResponseEntity.ok(ResponseDTO.success(response));
    }

    @DeleteMapping("/{masterProductId}/listings/{listingId}/link")
    @Operation(summary = "Detach a channel cell from its master (local only; no marketplace call)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> unlinkChannel(
            @PathVariable Long masterProductId, @PathVariable Long listingId) {
        channelLinkService.unlink(masterProductId, listingId);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    /**
     * ⚠️ 해제 경로({@code .../link})와 <b>다른 경로</b>다 — "떼어내기"와 "지우기"는 되돌릴 수 있는 정도가
     * 달라 한 엔드포인트로 합치지 않는다(2609_63/D13).
     */
    @DeleteMapping("/{masterProductId}/listings/{listingId}")
    @Operation(summary = "Delete a DRAFT channel cell (never registered on the marketplace)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> deleteDraftChannel(
            @PathVariable Long masterProductId, @PathVariable Long listingId) {
        channelLinkService.deleteDraftChannel(masterProductId, listingId);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
