package com.pms.controller;

import com.pms.domain.OrderStatus;
import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.OutboundConfirmRequest;
import com.pms.dto.response.OutboundResponse;
import com.pms.dto.response.StockMovementView;
import com.pms.service.stock.StockOutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 출고 확인 API — ADMIN 전용 (FEATURE_2609_28 / PLAN D11·D19).
 *
 * <p>{@link StockController}(입고·폐기·조정·잔량)와 경로 앞부분을 공유하지만 컨트롤러를 나눈다 —
 * 출고는 주문에서 출발하는 별도 흐름이고, 입고 화면이 실수로 {@code STOCK_OUT} 을 만들 수 없도록
 * 서비스도 API 도 갈라 둔다({@code StockLedgerService.record} 는 {@code STOCK_OUT} 을 거부한다).
 *
 * <p>🔴 <b>이 엔드포인트를 서버 내부에서 호출하지 않는다</b>(D18). 발송처리·송장 업로드·주문 동기화에
 * 훅으로 걸면 "사람의 확인"이 무너지고 원장이 창고를 설명하지 못하게 된다.
 *
 * @see StockOutService
 */
@RestController
@RequestMapping("/api/admin/stock/outbound")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class StockOutboundController {

    private final StockOutService stockOutService;

    /**
     * 아직 안 나간 주문 라인 + 소진할 물품·수량. 오래된 주문이 위에 온다.
     *
     * @param sellerId 판매자 필터 (생략 = 전 판매자, PLAN 2609_29 D4)
     * @param status   {@code PAID} | {@code PREPARING} (생략 = 둘 다)
     */
    @GetMapping
    public ResponseEntity<ResponseDTO<OutboundResponse>> outbound(
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) OrderStatus status) {
        return ResponseEntity.ok(ResponseDTO.success(stockOutService.outbound(sellerId, status)));
    }

    /** 사람이 확인한 만큼 출고를 기록한다. 부분 확인 가능, 남은 수량 초과는 400. */
    @PostMapping("/confirm")
    public ResponseEntity<ResponseDTO<List<StockMovementView>>> confirm(
            @Valid @RequestBody OutboundConfirmRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(stockOutService.confirm(request)));
    }
}
