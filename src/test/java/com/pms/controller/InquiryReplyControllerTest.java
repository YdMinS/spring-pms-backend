package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.dto.response.CustomerInquiryResponse;
import com.pms.dto.response.ReplyCapability;
import com.pms.exception.ResourceNotFoundException;
import com.pms.service.inquiry.InquiryReplyFailedException;
import com.pms.service.inquiry.InquiryReplyService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InquiryReplyController 통합 테스트 — 권한(401/403)·요청 검증·핸들러 매핑.
 *
 * 서비스는 @MockBean 이라 정책·전송이 돌지 않는다 → 400·404 는 목이 예외를 던지게 해서 <b>매핑</b>만
 * 고정한다(판정 자체는 InquiryReplyPolicyTest·InquiryReplyServiceImplTest 담당).
 */
class InquiryReplyControllerTest extends BaseIntegrationTest {

    @MockBean private InquiryReplyService inquiryReplyService;

    @Test
    void reply_requiresAuth() throws Exception {
        mockMvc.perform(post("/api/admin/inquiries/1/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"내일 출고 예정입니다.\"}"))
                .andExpect(status().isUnauthorized());

        verify(inquiryReplyService, never()).reply(anyLong(), anyString());
    }

    @Test
    void reply_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/inquiries/1/replies")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"내일 출고 예정입니다.\"}"))
                .andExpect(status().isForbidden());

        verify(inquiryReplyService, never()).reply(anyLong(), anyString());
    }

    @Test
    void reply_blankContent_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/inquiries/1/replies")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(inquiryReplyService, never()).reply(anyLong(), anyString());
    }

    @Test
    void reply_unknownInquiry_returns404() throws Exception {
        given(inquiryReplyService.reply(eq(999L), any()))
                .willThrow(new ResourceNotFoundException("Inquiry", 999L));

        mockMvc.perform(post("/api/admin/inquiries/999/replies")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"내일 출고 예정입니다.\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reply_notRepliable_returns400WithReason() throws Exception {
        given(inquiryReplyService.reply(eq(1L), any()))
                .willThrow(new IllegalArgumentException("이미 답변한 문의입니다."));

        mockMvc.perform(post("/api/admin/inquiries/1/replies")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"내일 출고 예정입니다.\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 답변한 문의입니다."));
    }

    @Test
    void reply_platformRejects_returns400WithRawMessage() throws Exception {
        // 쿠팡 원문을 화면이 그대로 띄울 수 있어야 한다.
        given(inquiryReplyService.reply(eq(1L), any()))
                .willThrow(new InquiryReplyFailedException("이미 답변이 등록된 문의입니다."));

        mockMvc.perform(post("/api/admin/inquiries/1/replies")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"내일 출고 예정입니다.\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 답변이 등록된 문의입니다."));
    }

    @Test
    void reply_adminRole_returns200WithLockedCapability() throws Exception {
        given(inquiryReplyService.reply(eq(1L), any())).willReturn(answered());

        mockMvc.perform(post("/api/admin/inquiries/1/replies")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"내일 출고 예정입니다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.replies[0].content").value("내일 출고 예정입니다."))
                // 성공 응답만으로 화면 컴포저가 잠긴다(D17) — 재조회 없이.
                .andExpect(jsonPath("$.data.replyCapability.canReply").value(false))
                .andExpect(jsonPath("$.data.replyCapability.reason").value("이미 답변한 문의입니다."));
    }

    private CustomerInquiryResponse answered() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 6, 10, 0);
        return new CustomerInquiryResponse(1L, "COUPANG", 7L, "쿠팡-메인", 5L, "테스트셀러",
                InquiryType.PRODUCT_QNA, InquiryStatus.ANSWERED, "ANSWERED",
                "I-1", null, "V-1", "P-1", null, null, "양말", "재입고 문의", null,
                now.minusDays(1), now, false,
                List.of(new com.pms.dto.response.CustomerInquiryReplyResponse(101L, "local-abc", null,
                        com.pms.domain.InquiryAuthorRole.SELLER, null, "내일 출고 예정입니다.", null, now)),
                null, null,
                new ReplyCapability(false, "이미 답변한 문의입니다.", 1, 1000, true, null));
    }
}
