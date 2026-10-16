package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ElevenstCancelRejectRequest;
import com.pms.dto.response.ElevenstCancelRequestResponse;
import com.pms.service.elevenst.ElevenstCancelRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 11st cancel requests on the order detail (FEATURE_2610_17 / D19 · D37 · D38 · D40). Reading is open to every
 * authenticated user (like the order list); approve / reject are ADMIN only (they write to 11st and cannot be undone,
 * same as the other order write buttons).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "11st Cancel Request", description = "11st cancel requests of an order line")
public class ElevenstCancelRequestController {

    private final ElevenstCancelRequestService cancelRequestService;

    @GetMapping("/api/orders/{orderItemId}/elevenst-cancel-requests")
    @Operation(summary = "Open 11st cancel requests of one order line")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<ElevenstCancelRequestResponse>>> list(@PathVariable Long orderItemId) {
        return ResponseEntity.ok(ResponseDTO.success(cancelRequestService.listOpen(orderItemId)));
    }

    @PostMapping("/api/admin/elevenst-cancel-requests/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Approve an 11st cancel request (1640, cannot be undone)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> approve(@PathVariable Long id) {
        cancelRequestService.approve(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    @PostMapping("/api/admin/elevenst-cancel-requests/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reject an 11st cancel request with the invoice the goods left with (1641)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> reject(@PathVariable Long id,
                                                    @Valid @RequestBody ElevenstCancelRejectRequest request) {
        cancelRequestService.reject(id, request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
