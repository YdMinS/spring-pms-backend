package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ManualAdjustRequest;
import com.pms.dto.request.ManualItemRequest;
import com.pms.dto.request.PurchaseRecordRequest;
import com.pms.dto.response.PurchaseListResponse;
import com.pms.service.PurchaseListService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * "오늘 구매 목록"(사입 리스트) API — ADMIN 전용.
 *
 * sellerId 쿼리 파라미터는 선택(없으면 전체 계정). 추출/조회는 {@link PurchaseListService} 경유.
 *
 * @see PurchaseListService
 */
@RestController
@RequestMapping("/api/admin/purchase-list")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PurchaseListController {

    private final PurchaseListService purchaseListService;

    /** 구매 목록 조회(집계). */
    @GetMapping
    public ResponseEntity<ResponseDTO<PurchaseListResponse>> getList(
            @RequestParam(required = false) Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(purchaseListService.getList(sellerId)));
    }

    /** 추출(재적재) 후 갱신된 목록 반환. */
    @PostMapping("/extract")
    public ResponseEntity<ResponseDTO<PurchaseListResponse>> extract(
            @RequestParam(required = false) Long sellerId) {
        purchaseListService.extract(sellerId);
        return ResponseEntity.ok(ResponseDTO.success(purchaseListService.getList(sellerId)));
    }

    /** 라인 구매 기록 추가(부분구매/정정). */
    @PostMapping("/items/{itemId}/purchases")
    public ResponseEntity<ResponseDTO<Void>> addPurchase(
            @PathVariable Long itemId,
            @Valid @RequestBody PurchaseRecordRequest request) {
        purchaseListService.addPurchase(itemId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDTO.success((Void) null));
    }

    /** 수동 라인 추가/누적. */
    @PostMapping("/manual")
    public ResponseEntity<ResponseDTO<Void>> addManual(
            @Valid @RequestBody ManualItemRequest request) {
        purchaseListService.addManual(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDTO.success((Void) null));
    }

    /** 라인 manualQty 절대값 교체. */
    @PatchMapping("/items/{itemId}")
    public ResponseEntity<ResponseDTO<Void>> adjustManual(
            @PathVariable Long itemId,
            @Valid @RequestBody ManualAdjustRequest request) {
        purchaseListService.adjustManual(itemId, request);
        return ResponseEntity.ok(ResponseDTO.success((Void) null));
    }
}
