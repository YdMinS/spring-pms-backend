package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.OrderCancelRequest;
import com.pms.service.OrderCancelResult;
import com.pms.service.OrderCancelService;
import com.pms.service.claim.ActionChoice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 발송 전 주문 취소 컨트롤러 (ADMIN 전용, PLAN 2609_25 D11).
 *
 * 주문 상세에서 고른 라인×수량을 쿠팡 취소 API 로 보낸다 — 결제완료는 즉시취소, 상품준비중은 출고중지다.
 * 발주처리 컨트롤러와 분리한다 — 같은 ADMIN 등급이지만 쓰기의 성격이 다르다(전환 vs 취소).
 *
 * ⚠️ 되돌릴 수 없고 쿠팡 판매자 점수가 하락한다. 확인 다이얼로그는 클라이언트 계약이다(D13).
 */
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Order Cancel", description = "Pre-shipment order cancel API (ADMIN only)")
public class OrderCancelController {

    private final OrderCancelService orderCancelService;

    @GetMapping("/cancel-reasons")
    @Operation(summary = "List cancel reasons",
            description = "Server-owned reason list ({code,label}); the same enum validates the request")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Cancel reason choices")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<List<ActionChoice>>> cancelReasons() {
        return ResponseEntity.ok(ResponseDTO.success(orderCancelService.availableReasons()));
    }

    @PostMapping("/cancel")
    @Operation(summary = "Cancel order lines before shipment",
            description = "Selected lines x quantity → account/order/box grouping → Coupang cancel API "
                    + "(ACCEPT=immediate cancel, INSTRUCT=stop shipment). Irreversible.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Cancel result (cancelled/failed/skipped/unsupported)")
    @ApiResponse(responseCode = "400", description = "Empty selection, duplicated line, quantity over the "
            + "cancellable amount, or no order line found")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<OrderCancelResult>> cancel(
            @Valid @RequestBody OrderCancelRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(orderCancelService.cancel(request)));
    }
}
