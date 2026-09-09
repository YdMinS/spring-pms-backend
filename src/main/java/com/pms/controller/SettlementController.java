package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.SettlementSyncResponse;
import com.pms.dto.response.SettlementSyncTargetResponse;
import com.pms.service.settlement.SettlementSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 정산 원장 동기화 트리거 API — <b>ADMIN 전용</b> (FEATURE_2609_30 / PLAN D11 · D17).
 *
 * <p>정산·손익은 경영 데이터라 구매목록·재고와 같은 등급이다({@code PurchaseListController} 미러).
 *
 * <p>⚠️ 조회 API 는 여기 없다 — 이 조각은 <b>받아서 저장</b>까지만이고, 지급 묶음·대사·차이 리포트는
 * 다음 조각(02)이 소유한다.
 *
 * <p>⚠️ {@code IllegalArgumentException} 은 {@code GlobalExceptionHandler} 가 400 으로 매핑한다 —
 * 여기서 다시 잡지 말 것.
 */
@RestController
@RequestMapping("/api/admin/settlement")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SettlementController {

    private final SettlementSyncService settlementSyncService;

    /**
     * 수동 갱신 — delta 창만 다시 읽는다. 우선순위: accountId &gt; sellerId &gt; 전체.
     *
     * <p>계정별 최소 간격(기본 10분) 안이면 마켓을 호출하지 않고 {@code skipped=true} 로 돌아온다.
     */
    @PostMapping("/sync")
    public ResponseEntity<ResponseDTO<SettlementSyncResponse>> sync(
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) Long accountId) {
        return ResponseEntity.ok(ResponseDTO.success(settlementSyncService.sync(sellerId, accountId)));
    }

    /**
     * 기간 지정 백필(계정 1건). 31일을 넘는 구간은 어댑터가 잘라서 여러 번 호출한다.
     * {@code from > to} 이거나 계정이 없으면 400.
     */
    @PostMapping("/sync/period")
    public ResponseEntity<ResponseDTO<SettlementSyncResponse>> syncPeriod(
            @RequestParam Long accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ResponseDTO.success(settlementSyncService.syncPeriod(accountId, from, to)));
    }

    /** 대상 채널 목록 + 마지막 갱신 시각. 자격증명은 포함하지 않는다. */
    @GetMapping("/sync/targets")
    public ResponseEntity<ResponseDTO<List<SettlementSyncTargetResponse>>> syncTargets(
            @RequestParam(required = false) Long sellerId) {
        return ResponseEntity.ok(ResponseDTO.success(settlementSyncService.targets(sellerId)));
    }
}
