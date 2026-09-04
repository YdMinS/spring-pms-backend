package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.OrderAcknowledgeRequest;
import com.pms.service.OrderAcknowledgeResult;
import com.pms.service.OrderAcknowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 발주처리 컨트롤러 (ADMIN 전용, PLAN 2609_17 D5).
 *
 * 사용자가 체크한 주문 라인들이 속한 박스를 쿠팡 발주처리(결제완료→상품준비중)로 보낸다.
 * 일괄(목록)과 개별(주문 상세)이 같은 엔드포인트를 쓴다(D6 — 판정 이중화 금지).
 *
 * 조회용 {@link OrderController}(`/api/orders`, 인증만)와 분리한다 —
 * 마켓에 되돌릴 수 없는 쓰기를 하는 작업이라 권한 등급이 다르다.
 */
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Order Acknowledge", description = "Order acknowledgement (ACCEPT→INSTRUCT) API (ADMIN only)")
public class OrderAcknowledgeController {

    private final OrderAcknowledgeService orderAcknowledgeService;

    @PostMapping("/acknowledge")
    @Operation(summary = "Acknowledge orders (ACCEPT→INSTRUCT)",
            description = "Selected order lines → box dedupe → Coupang acknowledgement API (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Acknowledge result (succeeded/failed/skipped/unsupported)")
    @ApiResponse(responseCode = "400", description = "Empty selection or no order line found")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    public ResponseEntity<ResponseDTO<OrderAcknowledgeResult>> acknowledge(
            @Valid @RequestBody OrderAcknowledgeRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(orderAcknowledgeService.acknowledge(request)));
    }
}
