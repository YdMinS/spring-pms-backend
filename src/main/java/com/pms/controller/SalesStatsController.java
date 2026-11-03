package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.ChannelSalesResponse;
import com.pms.dto.response.DailySalesResponse;
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

    // excludeUnshipped on ①②③④ (FEATURE_2611_04 D1 · D2 · D4): true = the 「출고 전 주문 제외」 switch of the sales
    // screens. Absent = false = the numbers as before, which is what the dashboard cards send. ⑤ does not take it.

    /**
     * ① Summary per seller. No {@code sellerId} = every seller.
     *
     * <p>The estimated settlement ({@code estSettlement}) follows the period and the switch like the sales
     * (FEATURE_2611_05 D6); the fixed cost taken out of it is judged as before. The scheduled payouts regardless of the
     * period are not sent (FEATURE_2611_05 D13).
     */
    @GetMapping("/summary")
    public ResponseEntity<ResponseDTO<List<SellerSalesResponse>>> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false, defaultValue = "false") boolean excludeUnshipped) {
        return ResponseEntity.ok(ResponseDTO.success(
                salesStatsService.summary(from, to, sellerId, excludeUnshipped)));
    }

    /** ② 채널(계정)별. 현금주의({@code paidAmount})는 여기서만 제공한다(PLAN D4-1). */
    @GetMapping("/by-channel")
    public ResponseEntity<ResponseDTO<List<ChannelSalesResponse>>> byChannel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false, defaultValue = "false") boolean excludeUnshipped) {
        return ResponseEntity.ok(ResponseDTO.success(
                salesStatsService.byChannel(from, to, sellerId, excludeUnshipped)));
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
            @RequestParam(required = false, defaultValue = "true") boolean crossChannel,
            @RequestParam(required = false, defaultValue = "false") boolean excludeUnshipped) {
        return ResponseEntity.ok(ResponseDTO.success(
                salesStatsService.byProduct(from, to, sellerId, crossChannel, excludeUnshipped)));
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
            @RequestParam Long accountId,
            @RequestParam(required = false, defaultValue = "false") boolean excludeUnshipped) {
        return ResponseEntity.ok(ResponseDTO.success(
                salesStatsService.lines(from, to, accountId, excludeUnshipped)));
    }

    /**
     * ⑤ Sales per sale date — the dashboard sales chart (FEATURE_2610_29 D22). One row per date of the period, a
     * date without sales included with zeros. Same period defaults and 400 as the endpoints above. No
     * {@code sellerId} = no seller filter (FEATURE_2610_30 D34).
     */
    @GetMapping("/daily")
    public ResponseEntity<ResponseDTO<List<DailySalesResponse>>> daily(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(salesStatsService.daily(from, to, sellerId)));
    }
}