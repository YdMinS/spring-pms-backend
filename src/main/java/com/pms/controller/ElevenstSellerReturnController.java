package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ElevenstSellerReturnRequest;
import com.pms.service.claim.ActionChoice;
import com.pms.service.elevenst.ElevenstSellerReturnService;
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
 * [판매자가 대신 반품 신청] of an 11st order line (FEATURE_2610_17 / D35 · D36 · D39 ⑤). The reason list is read by
 * any signed-in user (the form source); the request itself is ADMIN only (a write to 11st).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "11st Seller Return", description = "Seller return request on the buyer's behalf (11st)")
public class ElevenstSellerReturnController {

    private final ElevenstSellerReturnService sellerReturnService;

    @GetMapping("/api/orders/elevenst-return-reasons")
    @Operation(summary = "11st seller-return reason codes (1653 original names)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<ActionChoice>>> reasons() {
        return ResponseEntity.ok(ResponseDTO.success(ElevenstSellerReturnService.REASONS));
    }

    @PostMapping("/api/admin/orders/{orderItemId}/elevenst-return-request")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Request a return on the buyer's behalf (1653)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> request(@PathVariable Long orderItemId,
                                                     @Valid @RequestBody ElevenstSellerReturnRequest request) {
        sellerReturnService.request(orderItemId, request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
