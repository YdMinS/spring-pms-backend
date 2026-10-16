package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.OrderCancelRequest;
import com.pms.service.OrderCancelResult;
import com.pms.service.OrderCancelService;
import com.pms.service.claim.ActionChoice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Seller cancel controller before shipping (ADMIN only, PLAN 2609_25 D11).
 *
 * Sends the lines × quantity picked on the order detail to the Coupang cancel API — paid becomes an instant cancel,
 * preparing a shipment stop; an 11st line goes to the 11st sale refusal 1638 of the whole line (FEATURE_2610_17 /
 * D61). Kept apart from the order-confirmation controller — same ADMIN level, different kind of write (transition vs
 * cancel).
 *
 * ⚠️ Cannot be undone and lowers the marketplace seller score. The confirm dialog is the client contract (D13 · D61).
 */
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Order Cancel", description = "Pre-shipment order cancel API (ADMIN only)")
public class OrderCancelController {

    private final OrderCancelService orderCancelService;

    @GetMapping("/cancel-reasons")
    @Operation(summary = "List cancel reasons",
            description = "Server-owned reason list ({code,label}); the same enum validates the request")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Cancel reason choices")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<List<ActionChoice>>> cancelReasons() {
        return ResponseEntity.ok(ResponseDTO.success(orderCancelService.availableReasons()));
    }

    @PostMapping("/cancel")
    @Operation(summary = "Cancel order lines before shipment",
            description = "Selected lines x quantity → account/order/box grouping → Coupang cancel API "
                    + "(ACCEPT=immediate cancel, INSTRUCT=stop shipment); 11st lines → 11st sale refusal of the "
                    + "whole line (reasonCode + reasonText). Irreversible.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Cancel result (cancelled/failed/skipped/unsupported)")
    @ApiResponse(responseCode = "400", description = "Empty selection, duplicated line, quantity over the "
            + "cancellable amount, no order line found, or a missing / unlisted reason (Coupang reason · 11st "
            + "reasonCode · reasonText 1~200, FEATURE_2610_17 / D71)")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<OrderCancelResult>> cancel(
            @Valid @RequestBody OrderCancelRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(orderCancelService.cancel(request)));
    }
}
