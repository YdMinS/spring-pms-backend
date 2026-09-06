package com.pms.controller;

import com.pms.dto.common.ResponseDTO;
import com.pms.dto.request.InquiryReplyRequest;
import com.pms.dto.response.CustomerInquiryResponse;
import com.pms.service.inquiry.InquiryReplyService;
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
 * 고객문의 답변 전송 컨트롤러 (ADMIN 전용, FEATURE_2609_23 / PLAN §5).
 *
 * <p>조회({@link CustomerInquiryController}, {@code /api/inquiries}, 인증만)와 <b>경로부터 분리</b>한다 —
 * 마켓에 되돌릴 수 없는 쓰기를 하는 작업이라 권한 등급이 다르다({@link ClaimActionController} 와 같은 자세).
 *
 * <p>응답은 <b>갱신된 문의 1건</b>이다 — 스레드·관련 주문·재판정된 {@code replyCapability} 를 함께 담아
 * 화면이 재조회 없이 상태를 갱신하게 한다.
 */
@RestController
@RequestMapping("/api/admin/inquiries")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Inquiry Reply", description = "Customer inquiry reply delivery (ADMIN only)")
public class InquiryReplyController {

    private final InquiryReplyService inquiryReplyService;

    @PostMapping("/{id}/replies")
    @Operation(summary = "Send a reply to a customer inquiry",
            description = "Irreversible; the server re-evaluates reply capability before sending (ADMIN role required)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Reply sent; returns the refreshed inquiry")
    @ApiResponse(responseCode = "400", description = "Blank body, length violation, not repliable, or the marketplace rejected the reply")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Permission denied (ADMIN role required)")
    @ApiResponse(responseCode = "404", description = "Inquiry not found")
    @ApiResponse(responseCode = "502", description = "Delivery result unknown (timeout/network); local state untouched")
    public ResponseEntity<ResponseDTO<CustomerInquiryResponse>> reply(
            @PathVariable Long id,
            @Valid @RequestBody InquiryReplyRequest request) {
        // 길이 검증은 정책이 한다(유형마다 다르다) — 여기서는 빈 본문만 막는다.
        return ResponseEntity.ok(ResponseDTO.success(inquiryReplyService.reply(id, request.content())));
    }
}
