package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.LotteonDeliveryAppointmentRequest;
import com.pms.dto.response.LotteonInvoiceRecordsResponse;
import com.pms.dto.response.LotteonOrderChoicesResponse;
import com.pms.service.lotteon.LotteonOrderSellerService;
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
 * LotteON order-detail reads (FEATURE_2611_23 / D19 ⑥-5 · D58): delivery records and the LotteON code lists — open to
 * every authenticated user (same as the 11st {@code elevenst-delivery-lines} · {@code elevenst-choices}). The
 * [발송 약정일 안내] write ([138], D26) is ADMIN only (R24).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "LotteON Order Seller Actions",
        description = "LotteON delivery records, code lists and the shipping appointment notice")
public class LotteonOrderSellerController {

    private final LotteonOrderSellerService sellerService;

    @GetMapping("/api/orders/lotteon-choices")
    @Operation(summary = "LotteON code lists of [주문 취소] · [취소 거부] · [발송 약정일 안내] · return / exchange hold")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<LotteonOrderChoicesResponse>> choices() {
        return ResponseEntity.ok(ResponseDTO.success(sellerService.choices()));
    }

    @GetMapping("/api/orders/{orderItemId}/lotteon-invoice-records")
    @Operation(summary = "Lines of the same LotteON delivery bundle with their delivery records (invoice update)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<LotteonInvoiceRecordsResponse>>> invoiceRecords(
            @PathVariable Long orderItemId) {
        return ResponseEntity.ok(ResponseDTO.success(sellerService.invoiceRecords(orderItemId)));
    }

    @PostMapping("/api/admin/orders/{orderItemId}/lotteon-delivery-appointment")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "LotteON shipping appointment notice of the whole order ([138])")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<Void>> deliveryAppointment(
            @PathVariable Long orderItemId, @Valid @RequestBody LotteonDeliveryAppointmentRequest request) {
        sellerService.deliveryAppointment(orderItemId, request);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }
}
