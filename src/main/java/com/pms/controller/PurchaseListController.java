package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ManualAdjustRequest;
import com.pms.dto.request.ManualItemRequest;
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
 * "오늘 구매 목록"(사입 리스트) API — ADMIN 전용.
 *
 * <p>🔴 판매자 쿼리 파라미터가 없다(PLAN 2609_29 D11) — 조회도 추출도 항상 전체다.
 * 판매자는 입고 요청 본문의 귀속 값으로만 등장한다.
 *
 * 추출/조회는 {@link PurchaseListService} 경유.
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

    /** 구매 목록 조회(집계). */
    @GetMapping
    public ResponseEntity<ResponseDTO<PurchaseListResponse>> getList() {
        return ResponseEntity.ok(ResponseDTO.success(purchaseListService.getList()));
    }

    /**
     * 구매 완료 목록 조회(잔여<=0 && 구매>0).
     * from/to: 구매일(purchase_record.purchasedOn) 기준 기간 필터(경계 포함). 선택.
     */
    @GetMapping("/completed")
    public ResponseEntity<ResponseDTO<List<PurchaseProductGroup>>> getCompletedList(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ResponseDTO.success(purchaseListService.getCompletedList(from, to)));
    }

    /** 추출(재적재) 후 갱신된 목록 반환. */
    @PostMapping("/extract")
    public ResponseEntity<ResponseDTO<PurchaseListResponse>> extract() {
        purchaseListService.extract();
        return ResponseEntity.ok(ResponseDTO.success(purchaseListService.getList()));
    }

    /**
     * 입고 1회 — 구매기록(돈)과 재고 입고(실물)를 같은 트랜잭션에서 쓴다 (PLAN 2609_29 D1).
     *
     * <p>⚠️ 라인 경로({@code /items/{itemId}/purchases})는 없어졌다 — 구매기록이 주문 라인을 모른다(D3).
     */
    @PostMapping("/purchases")
    public ResponseEntity<ResponseDTO<PurchaseRecordResult>> addPurchase(
            @Valid @RequestBody PurchaseRecordRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(purchaseListService.addPurchase(request)));
    }

    /** 그 물품의 최근 구매이력 — 판매자 무관, 최신순(기본 5건). 목록 응답에 싣지 않고 지연 조회한다(D9). */
    @GetMapping("/purchases")
    public ResponseEntity<ResponseDTO<List<PurchaseRecordView>>> recentPurchases(
            @RequestParam Long productId,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_RECENT_PURCHASES) int limit) {
        return ResponseEntity.ok(ResponseDTO.success(purchaseListService.recentPurchases(productId, limit)));
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
