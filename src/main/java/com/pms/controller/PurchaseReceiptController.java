package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ReceiptCreateRequest;
import com.pms.dto.request.ReceiptUpdateRequest;
import com.pms.dto.response.ReceiptCreateResult;
import com.pms.dto.response.ReceiptDetailView;
import com.pms.dto.response.ReceiptSummaryView;
import com.pms.service.PurchaseReceiptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/**
 * Receipt check-in, header edit and receipt list API — ADMIN only (FEATURE_2610_18 D28 · D45 · D76 · D87 · D93 · D94 ·
 * D100).
 *
 * <p>⚠️ Both multipart parts are {@code required = false} on purpose: a missing part would end in the catch-all 500
 * handler, so the service answers 400 with a message instead.</p>
 *
 * @see PurchaseReceiptService
 */
@RestController
@RequestMapping("/api/admin/purchase-receipts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PurchaseReceiptController {

    private final PurchaseReceiptService purchaseReceiptService;

    /** Receipt check-in: {@code photo} part (jpeg/png) + {@code request} part (application/json). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResponseDTO<ReceiptCreateResult>> create(
            @RequestPart(value = "photo", required = false) MultipartFile photo,
            @Valid @RequestPart(value = "request", required = false) ReceiptCreateRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(purchaseReceiptService.create(request, photo)));
    }

    /** Receipt list, newest day first. from/to inclusive and required; sellerId = payer seller (omitted = all). */
    @GetMapping
    public ResponseEntity<ResponseDTO<List<ReceiptSummaryView>>> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) String productName) {
        return ResponseEntity.ok(ResponseDTO.success(purchaseReceiptService.search(from, to, sellerId, productName)));
    }

    /** One receipt with its photo and lines. */
    @GetMapping("/{receiptId}")
    public ResponseEntity<ResponseDTO<ReceiptDetailView>> get(@PathVariable Long receiptId) {
        return ResponseEntity.ok(ResponseDTO.success(purchaseReceiptService.get(receiptId)));
    }

    /** Receipt header edit — purchase day, card, payer seller (D100). Returns the receipt as saved. */
    @PutMapping("/{receiptId}")
    public ResponseEntity<ResponseDTO<ReceiptDetailView>> update(
            @PathVariable Long receiptId,
            @Valid @RequestBody ReceiptUpdateRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(purchaseReceiptService.update(receiptId, request)));
    }
}
