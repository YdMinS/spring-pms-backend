package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.PropagationApplyRequest;
import com.pms.dto.response.CostDeviation;
import com.pms.dto.response.PropagationApplyResult;
import com.pms.dto.response.PropagationPreview;
import com.pms.service.cost.CostPropagationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 원가 파급 API — ADMIN 전용 (FEATURE_2609_28 / PLAN D4·D19).
 *
 * <p>미리보기 → 확정의 두 단계다. 🔴 <b>미리보기 없이 확정만 부르는 화면 경로를 만들지 않는다</b> —
 * "12개 마스터 / 340개 셀이 바뀝니다"가 사람이 멈출 수 있는 유일한 지점이다.
 *
 * <p>🔴 <b>이 엔드포인트를 스케줄·동기화가 호출하지 않는다.</b> 판매가를 사람 모르게 움직이면 D4 가 무너진다.
 *
 * @see CostPropagationService
 */
@RestController
@RequestMapping("/api/admin/cost")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class CostPropagationController {

    /** 괴리 목록 기본 건수. 사람이 훑어보는 목록이라 상한은 서비스가 다시 조인다. */
    private static final int DEFAULT_DEVIATION_LIMIT = 20;

    private final CostPropagationService costPropagationService;

    /**
     * 파급 대상 미리보기(dry-run). 아무것도 저장하지 않는다.
     *
     * @param since 이 날짜 이후의 매입만 본다(생략 = 최근 7일)
     */
    @GetMapping("/propagation/preview")
    public ResponseEntity<ResponseDTO<PropagationPreview>> preview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        return ResponseEntity.ok(ResponseDTO.success(costPropagationService.preview(since)));
    }

    /** 사람이 고른 마스터만 파급한다. 셀 단위 부분 실패는 PARTIAL 로 보고하고 되돌리지 않는다. */
    @PostMapping("/propagation/apply")
    public ResponseEntity<ResponseDTO<PropagationApplyResult>> apply(
            @Valid @RequestBody PropagationApplyRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(costPropagationService.apply(request.masterIds())));
    }

    /** 기준가가 최근 매입가와 많이 어긋난 물품 목록. 판단 재료일 뿐, 자동 갱신하지 않는다. */
    @GetMapping("/deviation")
    public ResponseEntity<ResponseDTO<List<CostDeviation>>> deviation(
            @RequestParam(required = false, defaultValue = "" + DEFAULT_DEVIATION_LIMIT) int limit) {
        return ResponseEntity.ok(ResponseDTO.success(costPropagationService.deviations(limit)));
    }
}
