package com.pms.controller;

import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.OrderClaimResponse;
import com.pms.service.claim.ClaimQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 클레임(반품·교환) 조회 API. 인증된 사용자 대상 — 권한은 SecurityConfig 의 anyRequest().authenticated() 적용
 * ({@link OrderController} 와 동일). 역할 제한이 없으므로 {@code @PreAuthorize} 를 붙이지 않는다.
 */
@RestController
@RequestMapping("/api/claims")
@RequiredArgsConstructor
public class OrderClaimController {

    private final ClaimQueryService claimQueryService;

    /**
     * 클레임 목록 조회 (최신 접수순). type 생략 시 RETURN, 나머지 필터는 전부 선택.
     * from·to(yyyy-MM-dd)를 함께 주면 그 기간(접수일 기준, 양끝 포함)을, 없으면 최근 sync-days 를 반환한다.
     * 둘 중 하나만 주거나 from > to 면 400.
     */
    @GetMapping
    public ResponseEntity<ResponseDTO<List<OrderClaimResponse>>> list(
            @RequestParam(required = false, defaultValue = "RETURN") ClaimType type,
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(ResponseDTO.success(
                claimQueryService.getClaims(type, status, sellerId, from, to, keyword)));
    }

    /** 클레임 단건 조회. 없는 id 면 404. */
    @GetMapping("/{id}")
    public ResponseEntity<ResponseDTO<OrderClaimResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ResponseDTO.success(claimQueryService.getClaim(id)));
    }
}
