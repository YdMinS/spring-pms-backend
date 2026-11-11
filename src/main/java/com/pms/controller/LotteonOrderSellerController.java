package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.LotteonInvoiceRecordsResponse;
import com.pms.service.lotteon.LotteonOrderSellerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * LotteON order-detail reads (FEATURE_2611_23 / D19 ⑥-5 · D58). Reading is open to every authenticated user (same as
 * the 11st {@code elevenst-delivery-lines}).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "LotteON Order Seller Actions", description = "LotteON delivery records for the invoice update")
public class LotteonOrderSellerController {

    private final LotteonOrderSellerService sellerService;

    @GetMapping("/api/orders/{orderItemId}/lotteon-invoice-records")
    @Operation(summary = "Lines of the same LotteON delivery bundle with their delivery records (invoice update)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDTO<List<LotteonInvoiceRecordsResponse>>> invoiceRecords(
            @PathVariable Long orderItemId) {
        return ResponseEntity.ok(ResponseDTO.success(sellerService.invoiceRecords(orderItemId)));
    }
}
