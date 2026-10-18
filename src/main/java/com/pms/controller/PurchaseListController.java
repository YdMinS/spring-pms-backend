package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ManualAdjustRequest;
import com.pms.dto.request.ManualItemRequest;
import com.pms.dto.request.ManualSellerRequest;
import com.pms.dto.request.PurchaseCheckRequest;
import com.pms.dto.request.PurchaseRecordRequest;
import com.pms.dto.response.PurchaseListResponse;
import com.pms.dto.response.PurchaseProductGroup;
import com.pms.dto.response.PurchaseRecordResult;
import com.pms.dto.response.PurchaseRecordView;
import com.pms.service.PurchaseListService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * "Today's purchase list" API — ADMIN only.
 *
 * <p>🔴 {@code sellerId} is a view filter on the reads only (FEATURE_2610_09 D1 · D4). The extract takes no seller —
 * it always reloads every seller (D19).
 *
 * Extract and reads go through {@link PurchaseListService}.
 *
 * @see PurchaseListService
 */
@RestController
@RequestMapping("/api/admin/purchase-list")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PurchaseListController {

    /** 최근 구매이력 기본 건수 (PLAN 2609_29 D9·D21). */
    private static final int DEFAULT_RECENT_PURCHASES = 5;

    private final PurchaseListService purchaseListService;

    /** Purchase list tab (rows to buy + 「오늘 넘김」 + unmapped orders). sellerId omitted = 「전체」. */
    @GetMapping
    public ResponseEntity<ResponseDTO<PurchaseListResponse>> getList(
            @RequestParam(required = false) Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(purchaseListService.getList(sellerId)));
    }

    /**
     * Completed tab — rows whose shares are all processed (FEATURE_2610_09 D57 · D78).
     * from/to: processing day filter, inclusive, optional (D63 · D81 · D85). sellerId omitted = 「전체」.
     */
    @GetMapping("/completed")
    public ResponseEntity<ResponseDTO<List<PurchaseProductGroup>>> getCompletedList(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(purchaseListService.getCompletedList(from, to, sellerId)));
    }

    /** Re-extract every seller, then return the 「전체」 list (D4 — screens reload with their own filter instead). */
    @PostMapping("/extract")
    public ResponseEntity<ResponseDTO<PurchaseListResponse>> extract() {
        purchaseListService.extract();
        return ResponseEntity.ok(ResponseDTO.success(purchaseListService.getList(null)));
    }

    /**
     * One check-in — the purchase record, plus stock while the stock link setting is on (PLAN 2609_29 D1 ·
     * FEATURE_2610_09 D65).
     *
     * <p>⚠️ The line path ({@code /items/{itemId}/purchases}) is gone — purchase records do not know order lines (D3).
     */
    @PostMapping("/purchases")
    public ResponseEntity<ResponseDTO<PurchaseRecordResult>> addPurchase(
            @Valid @RequestBody PurchaseRecordRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(purchaseListService.addPurchase(request)));
    }

    /** Recent purchases of a product, newest first (default 5). sellerId omitted = every seller (D7 · D25). */
    @GetMapping("/purchases")
    public ResponseEntity<ResponseDTO<List<PurchaseRecordView>>> recentPurchases(
            @RequestParam Long productId,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_RECENT_PURCHASES) int limit) {
        return ResponseEntity.ok(ResponseDTO.success(purchaseListService.recentPurchases(productId, sellerId, limit)));
    }

    /** Add a manual line or accumulate into the (product × seller) manual line (D11 · D16). */
    @PostMapping("/manual")
    public ResponseEntity<ResponseDTO<Void>> addManual(
            @Valid @RequestBody ManualItemRequest request) {
        purchaseListService.addManual(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDTO.success((Void) null));
    }

    /** Replace a manual line's manualQty (1 or more). Order line → 400 (D23). */
    @PatchMapping("/items/{itemId}")
    public ResponseEntity<ResponseDTO<Void>> adjustManual(
            @PathVariable Long itemId,
            @Valid @RequestBody ManualAdjustRequest request) {
        purchaseListService.adjustManual(itemId, request);
        return ResponseEntity.ok(ResponseDTO.success((Void) null));
    }

    /** Move a manual line to another seller. Order line → 400 · that seller's manual line exists → 409 (D13 · D26). */
    @PatchMapping("/items/{itemId}/seller")
    public ResponseEntity<ResponseDTO<Void>> changeManualSeller(
            @PathVariable Long itemId,
            @Valid @RequestBody ManualSellerRequest request) {
        purchaseListService.changeManualSeller(itemId, request);
        return ResponseEntity.ok(ResponseDTO.success((Void) null));
    }

    /** Delete a manual line. Order line → 400 (D77). */
    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<ResponseDTO<Void>> deleteManual(@PathVariable Long itemId) {
        purchaseListService.deleteManual(itemId);
        return ResponseEntity.ok(ResponseDTO.success((Void) null));
    }

    /** [매입 완료] · [오늘 매입 안 함] on one (product × seller) share (D57 · D59 · D61). */
    @PostMapping("/checks")
    public ResponseEntity<ResponseDTO<Void>> check(@Valid @RequestBody PurchaseCheckRequest request) {
        purchaseListService.check(request);
        return ResponseEntity.ok(ResponseDTO.success((Void) null));
    }

    /** [완료 취소] · [되돌리기] — the record stays, marked cancelled (D62 · D67 · D79). */
    @PostMapping("/checks/{checkId}/cancel")
    public ResponseEntity<ResponseDTO<Void>> cancelCheck(@PathVariable Long checkId) {
        purchaseListService.cancelCheck(checkId);
        return ResponseEntity.ok(ResponseDTO.success((Void) null));
    }
}
