package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ElevenstDeliveryDelayRequest;
import com.pms.dto.request.ElevenstVisitPickupRequest;
import com.pms.dto.response.ElevenstChoicesResponse;
import com.pms.dto.response.ElevenstDeliveryLineResponse;
import com.pms.service.elevenst.ElevenstOrderSellerService;
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
 * 11st order-detail seller actions (FEATURE_2610_17 / D61 · D62 · D63 · D64 · D66 · D67). Reading is open to every
 * authenticated user (like the order list); the writes are ADMIN only (they write to 11st and cannot be undone).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "11st Order Seller Actions", description = "11st shipping delay, visit pickup, reserved-sale stock")
public class ElevenstOrderSellerController {

    private final ElevenstOrderSellerService sellerService;

    @GetMapping("/api/orders/elevenst-choices")
    @Operation(summary = "11st code lists of [주문 취소] · [책임사유 오류로 거부] · [발송지연 안내]")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<ElevenstChoicesResponse>> choices() {
        return ResponseEntity.ok(ResponseDTO.success(sellerService.choices()));
    }

    @GetMapping("/api/orders/{orderItemId}/elevenst-delivery-lines")
    @Operation(summary = "Lines of the same order and 11st delivery (partial / split shipment pickers)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<ElevenstDeliveryLineResponse>>> deliveryLines(
            @PathVariable Long orderItemId) {
        return ResponseEntity.ok(ResponseDTO.success(sellerService.deliveryLines(orderItemId)));
    }

    @PostMapping("/api/admin/orders/{orderItemId}/elevenst-delivery-delay")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "11st shipping-delay notice (6728)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> deliveryDelay(@PathVariable Long orderItemId,
                                                           @Valid @RequestBody ElevenstDeliveryDelayRequest request) {
        sellerService.deliveryDelay(orderItemId, request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    @PostMapping("/api/admin/orders/{orderItemId}/elevenst-visit-pickup")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "11st visit pickup done (7052, cannot be undone)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> visitPickup(@PathVariable Long orderItemId,
                                                         @Valid @RequestBody ElevenstVisitPickupRequest request) {
        sellerService.visitPickup(orderItemId, request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    @PostMapping("/api/admin/orders/{orderItemId}/elevenst-stock-confirm")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "11st reserved-sale stock arrived (1773, cannot be undone)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> stockConfirm(@PathVariable Long orderItemId) {
        sellerService.stockConfirm(orderItemId);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
