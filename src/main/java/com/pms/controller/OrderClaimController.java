package com.pms.controller;

import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.dto.common.ResponseDTO;
import com.pms.dto.response.ClaimSyncResponse;
import com.pms.dto.response.OrderClaimResponse;
import com.pms.service.claim.ClaimQueryService;
import com.pms.service.claim.ClaimSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final ClaimSyncService claimSyncService;

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

    /**
     * 채널 1개의 반품·교환을 마켓에서 다시 가져온다 (FEATURE_2609_70 / D14). 클레임만 보려고 주문 동기화
     * 전체를 돌리지 않기 위한 입구이며, 적재 규칙은 주문 동기화 경로와 같은 한 벌이다.
     *
     * <p>여러 채널은 화면이 하나씩 부른다 — 진행 상황과 채널별 실패를 그리기 위해서다.
     * 없는 계정이면 404, 클레임 조회를 지원하지 않는 플랫폼이면 400. 같은 채널이 이미 동기화 중이면
     * 쿠팡을 치지 않고 {@code skipped=true} 로 돌아온다.
     *
     * <p>⚠️ 마켓에 쓰는 동작이 아니라 읽어와 저장하는 동작이라 조회 경로에 둔다
     * ({@code POST /api/orders/sync}·{@code POST /api/inquiries/sync} 와 같은 자리).
     * 클레임 처리 액션만 {@code /api/admin/…} 으로 분리돼 있다.
     */
    @PostMapping("/sync")
    public ResponseEntity<ResponseDTO<ClaimSyncResponse>> sync(@RequestParam Long accountId) {
        return ResponseEntity.ok(ResponseDTO.success(claimSyncService.sync(accountId)));
    }
}
