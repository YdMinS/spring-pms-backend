package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.LotteonCancelRejectRequest;
import com.pms.dto.response.LotteonCancelRequestResponse;
import com.pms.service.lotteon.LotteonCancelRequestService;
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
 * LotteON cancel requests on the order detail (FEATURE_2611_23 / D24 · R24). Reading is open to every authenticated
 * user (like the order list); approve / reject are ADMIN only (they write to LotteON and cannot be undone, same as
 * the other order write buttons).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "LotteON Cancel Request", description = "LotteON cancel requests of an order line")
public class LotteonCancelRequestController {

    private final LotteonCancelRequestService cancelRequestService;

    @GetMapping("/api/orders/{orderItemId}/lotteon-cancel-requests")
    @Operation(summary = "Open LotteON cancel requests of one order line")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<LotteonCancelRequestResponse>>> list(@PathVariable Long orderItemId) {
        return ResponseEntity.ok(ResponseDTO.success(cancelRequestService.listOpen(orderItemId)));
    }

    @PostMapping("/api/admin/lotteon-cancel-requests/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Approve a LotteON cancel request ([60], cannot be undone)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> approve(@PathVariable Long id) {
        cancelRequestService.approve(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    @PostMapping("/api/admin/lotteon-cancel-requests/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reject a LotteON cancel request with a listed reason ([59])")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> reject(@PathVariable Long id,
                                                    @Valid @RequestBody LotteonCancelRejectRequest request) {
        cancelRequestService.reject(id, request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
