package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.OrderItemResponse;
import com.pms.dto.response.OrderSyncResponse;
import com.pms.service.coupang.OrderQueryService;
import com.pms.service.coupang.OrderSyncFacade;
import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 주문 조회 + 동기화 트리거 API. 인증된 사용자(로그인) 대상 — 권한은 SecurityConfig 의 anyRequest().authenticated() 적용.
 *
 * 동기화는 반드시 {@link OrderSyncFacade}(단일 진입점)만 호출한다(중복 로직 금지).
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderQueryService queryService;
    private final OrderSyncFacade syncFacade;

    /** 주문 목록 조회. sellerId 없으면 전체. */
    @GetMapping
    public ResponseEntity<ResponseDTO<List<OrderItemResponse>>> list(
            @RequestParam(required = false) Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(queryService.list(sellerId)));
    }

    /**
     * 동기화 트리거(새로고침). 동기화 후 목록까지 함께 반환(클라 추가 GET 불필요).
     * 우선순위: accountId(단건) > sellerId(셀러 단위) > 전체.
     */
    @PostMapping("/sync")
    public ResponseEntity<ResponseDTO<OrderSyncResponse>> sync(
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) Long accountId) {
        OrderSyncResult result = (accountId != null) ? syncFacade.sync(accountId)
                : (sellerId != null) ? syncFacade.syncBySeller(sellerId)
                : syncFacade.syncAll();
        return ResponseEntity.ok(ResponseDTO.success(
                new OrderSyncResponse(result, queryService.list(sellerId))));
    }
}
