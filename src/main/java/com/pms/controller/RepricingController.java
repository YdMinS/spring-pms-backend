package com.pms.controller;

import com.pms.domain.Platform;
import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.RepricingCandidatesResponse;
import com.pms.service.price.RepricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 마진 경보 API — ADMIN 전용 (FEATURE_2609_39 / PLAN D2).
 *
 * <p>🔴 <b>조회는 아무것도 바꾸지 않는다.</b> 비용(택배비·박스비·수수료율·매입가)이 움직여도 판매가는 저절로
 * 재계산되지 않는다 — 그 구간을 드러내는 것이 이 화면의 목적이고, 실행은 사람이 별도 액션으로 한다(D1).</p>
 *
 * @see RepricingService
 */
@RestController
@RequestMapping("/api/admin/repricing")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class RepricingController {

    private final RepricingService repricingService;

    /**
     * 대응 필요 목록 + 판매자 × 채널 집계. 저장·전송 없음.
     *
     * @param sellerId 판매자 필터(생략 = 전 판매자)
     * @param platform 채널 필터(생략 = 전 채널)
     * @param scope    {@code BELOW}(기본, 대응 필요만) 또는 {@code ALL}(대상 전부)
     */
    @GetMapping("/candidates")
    public ResponseEntity<ResponseDTO<RepricingCandidatesResponse>> candidates(
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) RepricingService.Scope scope) {
        // Platform.from(null) 은 400 이므로 널 체크는 경계인 여기서 한다(2609_26 / D16 규칙).
        Platform resolved = platform == null ? null : Platform.from(platform);
        return ResponseEntity.ok(ResponseDTO.success(
                repricingService.candidates(sellerId, resolved, scope)));
    }
}
