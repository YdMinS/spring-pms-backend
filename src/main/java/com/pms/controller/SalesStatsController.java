package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.ChannelSalesResponse;
import com.pms.dto.response.ProductProfitResponse;
import com.pms.dto.response.SalesLineView;
import com.pms.dto.response.SellerSalesResponse;
import com.pms.service.sales.SalesStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 매출 집계 조회 API — <b>ADMIN 전용</b> (FEATURE_2609_30 / PLAN D17 · 03).
 *
 * <p>손익은 경영 데이터라 정산·구매목록·재고와 같은 등급이다({@code SettlementController} 미러).
 *
 * <p>전부 로컬 DB 집계다 — 마켓을 부르지 않으므로 화면이 자주 열려도 API 호출량이 되지 않는다.
 * 기간 기본값은 {@code to} = 오늘, {@code from} = 이번 달 1일이고, {@code from > to} 면 400이다
 * ({@code IllegalArgumentException} → {@code GlobalExceptionHandler}, 여기서 다시 잡지 말 것).
 */
@RestController
@RequestMapping("/api/admin/sales")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SalesStatsController {

    private final SalesStatsService salesStatsService;

    /**
     * ① 판매자별 요약. {@code sellerId} 없으면 전 판매자.
     *
     * <p>🔴 {@code pendingPayout}("받을 돈")만 <b>기간 무관</b>이다 — 축이 매출인식일이라 판매일 기간과
     * 겹치지 않는다(PLAN D4). 화면은 이 값을 기간 라벨 밖에 놓아야 한다.
     */
    @GetMapping("/summary")
    public ResponseEntity<ResponseDTO<List<SellerSalesResponse>>> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(salesStatsService.summary(from, to, sellerId)));
    }

    /** ② 채널(계정)별. 현금주의({@code paidAmount})는 여기서만 제공한다(PLAN D4-1). */
    @GetMapping("/by-channel")
    public ResponseEntity<ResponseDTO<List<ChannelSalesResponse>>> byChannel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(salesStatsService.byChannel(from, to, sellerId)));
    }

    /**
     * ③ 상품별 수익성.
     *
     * @param crossChannel {@code true} = 마스터 상품 단위("이 상품이 전체적으로 돈이 되나") /
     *                     {@code false} = 마스터 × 채널("쿠팡은 남는데 네이버는 안 남는다")
     */
    @GetMapping("/by-product")
    public ResponseEntity<ResponseDTO<List<ProductProfitResponse>>> byProduct(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false, defaultValue = "true") boolean crossChannel) {
        return ResponseEntity.ok(ResponseDTO.success(
                salesStatsService.byProduct(from, to, sellerId, crossChannel)));
    }

    /**
     * ④ 판매 내역 — 한 채널의 주문 라인 목록. {@code accountId} <b>필수</b>.
     *
     * <p>①②③ 과 달리 접지 않은 목록이라 채널을 지정하지 않으면 전 채널 라인이 통째로 나온다 —
     * 그래서 계정을 필수로 받고, 없으면 400 이다.
     */
    @GetMapping("/lines")
    public ResponseEntity<ResponseDTO<List<SalesLineView>>> lines(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam Long accountId) {
        return ResponseEntity.ok(ResponseDTO.success(salesStatsService.lines(from, to, accountId)));
    }
}