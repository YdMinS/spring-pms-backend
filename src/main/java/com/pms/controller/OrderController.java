package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.OrderItemResponse;
import com.pms.dto.response.OrderMonthResponse;
import com.pms.dto.response.OrderSyncResponse;
import com.pms.dto.response.SyncTargetResponse;
import com.pms.service.coupang.OrderQueryService;
import com.pms.service.coupang.OrderSyncFacade;
import com.pms.service.coupang.OrderSyncFacade.OrderSyncResult;
import com.pms.service.coupang.SyncTargetService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
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
    private final SyncTargetService syncTargetService;

    /**
     * 주문 목록 조회. sellerId 없으면 전체.
     * from·to(yyyy-MM-dd)를 함께 주면 그 기간(결제일 기준, 양끝 포함)을, 없으면 최근 sync-days 를 반환한다.
     * 둘 중 하나만 주거나 from > to 면 400.
     */
    @GetMapping
    public ResponseEntity<ResponseDTO<List<OrderItemResponse>>> list(
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ResponseDTO.success(queryService.list(sellerId, from, to)));
    }

    /**
     * 주문이 존재하는 달과 건수(최신순). 기간 드롭다운이 데이터 없는 달을 라벨링하는 데 쓴다.
     * 판매자 필터와 무관한 전체 기준이며, 데이터가 없으면 빈 배열.
     */
    @GetMapping("/months")
    public ResponseEntity<ResponseDTO<List<OrderMonthResponse>>> months() {
        return ResponseEntity.ok(ResponseDTO.success(queryService.months()));
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
                new OrderSyncResponse(result, queryService.list(sellerId, null, null))));
    }

    /**
     * 기간 지정 주문 불러오기 (과거 달 백필). 계정 1건 단위 — 여러 계정은 클라이언트가 순차 호출한다.
     * 정기 동기화와 달리 취소 보정·동기화 상태 기록을 하지 않는다(FEATURE_2609_10 D4·D5).
     * accountId/from/to 는 전부 필수이며, from > to 이거나 간격이 31일 이상이면 400.
     */
    @PostMapping("/sync/period")
    public ResponseEntity<ResponseDTO<OrderSyncResult>> syncPeriod(
            @RequestParam Long accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ResponseDTO.success(syncFacade.syncPeriod(accountId, from, to)));
    }

    /**
     * 동기화 대상 채널 목록(진행 표시·재시도용). 자격증명은 포함하지 않는다.
     * sellerId 없으면 전체. 대상이 없으면 빈 배열.
     */
    @GetMapping("/sync/targets")
    public ResponseEntity<ResponseDTO<List<SyncTargetResponse>>> syncTargets(
            @RequestParam(required = false) Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(syncTargetService.list(sellerId)));
    }
}
