package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.ClaimActionRequest;
import com.pms.dto.response.ClaimActionResponse;
import com.pms.service.claim.ClaimActionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 클레임 처리 액션 컨트롤러 (ADMIN 전용, FEATURE_2609_21 / PLAN D13).
 *
 * <p>조회({@link OrderClaimController}, {@code /api/claims}, 인증만)와 <b>경로부터 분리</b>한다 —
 * 마켓에 되돌릴 수 없는 쓰기를 하는 작업이라 권한 등급이 다르다(발주처리와 같은 posture).
 *
 * <p>엔드포인트는 <b>하나</b>다. 액션마다 경로를 파면 7개가 되고 권한·감사·중복 가드가 7벌로 흩어진다.
 *
 * <p>🔴 이 컨트롤러가 액션의 <b>유일한 호출자</b>다(D4) — 동기화 경로는 액션을 부르지 않는다.
 */
@RestController
@RequestMapping("/api/admin/claims")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Claim Action", description = "Return/exchange claim processing actions (ADMIN only)")
public class ClaimActionController {

    private final ClaimActionService claimActionService;

    @PostMapping("/{id}/actions")
    @Operation(summary = "Execute a claim processing action",
            description = "Whitelisted by platform_status; duplicate-guarded per receipt (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Action succeeded")
    @ApiResponse(responseCode = "400", description = "Missing required field, not allowed in current state, or unsupported platform")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    @ApiResponse(responseCode = "404", description = "Claim not found")
    @ApiResponse(responseCode = "409", description = "The receipt already had this action succeed")
    @ApiResponse(responseCode = "502", description = "Marketplace rejected the action (raw code/message in data)")
    public ResponseEntity<ResponseDTO<ClaimActionResponse>> execute(
            @PathVariable Long id,
            @Valid @RequestBody ClaimActionRequest request) {
        // 액션별 필수 필드는 서비스가 아니라 요청 DTO + 액션 메타(requires)가 판정한다(PLAN §5).
        request.validateFor();
        return ResponseEntity.ok(ResponseDTO.success(claimActionService.execute(id, request)));
    }
}
