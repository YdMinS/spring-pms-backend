package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.AccountFixedCostReplaceRequest;
import com.pms.dto.request.PlatformFixedCostPatchRequest;
import com.pms.dto.request.PlatformFixedCostRequest;
import com.pms.dto.response.AccountFixedCostResponse;
import com.pms.dto.response.PlatformFixedCostResponse;
import com.pms.service.sales.FixedCostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 고정비 카탈로그 + 채널 연결 API — <b>ADMIN 전용</b> (FEATURE_2609_33 / PLAN 2609_33 D10).
 *
 * <p>비용 마스터는 택배비·상자비·수수료와 같은 등급이다({@code SalesStatsController} 미러).
 *
 * <p>🔴 카탈로그 항목은 <b>연결이 없을 때만</b> 지워진다(409). 그만 부과하려는 것이라면
 * {@code PATCH {active:false}} 다 — 지우면 그 채널의 과거 순이익 근거가 사라진다.
 *
 * <p>잘못된 값({@code YYYY-MM} 위반 · 시작월 > 종료월 · 모르는 부과 방식 · 다른 플랫폼 항목)은
 * {@code IllegalArgumentException} → {@code GlobalExceptionHandler} 가 400 으로 낸다(여기서 다시 잡지 말 것).
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class FixedCostController {

    private final FixedCostService fixedCostService;

    /** 카탈로그 전체(비활성 포함 — 화면에서 다시 켤 수 있어야 한다). */
    @GetMapping("/fixed-costs")
    public ResponseEntity<ResponseDTO<List<PlatformFixedCostResponse>>> list() {
        return ResponseEntity.ok(ResponseDTO.success(fixedCostService.list()));
    }

    /** 항목 추가. 같은 {@code (platform, name)} 이 이미 있으면 409. */
    @PostMapping("/fixed-costs")
    public ResponseEntity<ResponseDTO<PlatformFixedCostResponse>> create(
            @Valid @RequestBody PlatformFixedCostRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(fixedCostService.create(request)));
    }

    /** 부분 수정 — null 필드는 기존 값을 유지한다. */
    @PatchMapping("/fixed-costs/{id}")
    public ResponseEntity<ResponseDTO<PlatformFixedCostResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody PlatformFixedCostPatchRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(fixedCostService.update(id, request)));
    }

    /** 삭제. 연결이 하나라도 있으면 409. */
    @DeleteMapping("/fixed-costs/{id}")
    public ResponseEntity<ResponseDTO<Void>> delete(@PathVariable Long id) {
        fixedCostService.delete(id);
        return ResponseEntity.ok(ResponseDTO.success(null));
    }

    /** 채널에 걸린 항목 목록. {@code thresholdAmount} 는 실효값(override ?? 카탈로그 기본값)이다. */
    @GetMapping("/marketplace-account/{id}/fixed-costs")
    public ResponseEntity<ResponseDTO<List<AccountFixedCostResponse>>> listForAccount(
            @PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(fixedCostService.listForAccount(id)));
    }

    /**
     * 채널 연결 <b>멱등 replace</b> — 보낸 목록이 그 채널의 전부다(빠진 항목은 끊긴다).
     *
     * <p>🔴 그 계정의 플랫폼에 속한 항목만 받는다 — 다른 플랫폼 항목이 섞이면 400.
     */
    @PutMapping("/marketplace-account/{id}/fixed-costs")
    public ResponseEntity<ResponseDTO<List<AccountFixedCostResponse>>> replaceForAccount(
            @PathVariable Long id,
            @Valid @RequestBody AccountFixedCostReplaceRequest request) {
        return ResponseEntity.ok(ResponseDTO.success(fixedCostService.replaceForAccount(id, request)));
    }
}
