package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.InquiryStatus;
import com.pms.domain.InquiryType;
import com.pms.dto.response.CustomerInquiryResponse;
import com.pms.dto.response.InquiryTypeCatalogResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.service.inquiry.InquiryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CustomerInquiryController 통합 테스트 — 인증(401)·목록·유형 카탈로그·400/404 핸들러 매핑.
 *
 * 서비스는 @MockBean 이라 검증 로직이 돌지 않는다 → 400·404 는 목이 예외를 던지게 해서
 * <b>핸들러 매핑</b>만 고정한다(검증 자체는 InquiryQueryServiceImplTest 담당).
 * 403 테스트는 없다 — 역할 제한 없는 조회 API 이고(OrderClaimController 와 동일),
 * 일반 사용자 토큰으로 200 이 나오는 것이 그 사실을 고정한다.
 */
class CustomerInquiryControllerTest extends BaseIntegrationTest {

    @MockBean private InquiryQueryService inquiryQueryService;

    @Test
    void getInquiries_returnsList_withUserToken() throws Exception {
        given(inquiryQueryService.getInquiries(any(), any(), any(), any(), any(), any(), any()))
                .willReturn(List.of(sample()));

        mockMvc.perform(get("/api/inquiries").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].platform").value("COUPANG"))
                .andExpect(jsonPath("$.data[0].inquiryType").value("PRODUCT_QNA"))
                .andExpect(jsonPath("$.data[0].status").value("UNANSWERED"))
                .andExpect(jsonPath("$.data[0].linked").value(false));
    }

    @Test
    void getInquiries_halfOpenPeriod_returns400() throws Exception {
        given(inquiryQueryService.getInquiries(any(), any(), any(), any(), any(), any(), any()))
                .willThrow(new IllegalArgumentException("조회 기간은 from 과 to 를 함께 지정해야 합니다."));

        mockMvc.perform(get("/api/inquiries")
                        .param("from", "2026-09-01")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getTypes_returnsPlatformCatalog() throws Exception {
        // D4 — 프론트의 유일한 유형 원천이다. 리터럴 경로가 /{id} 에 가려지지 않는 것도 함께 고정한다.
        given(inquiryQueryService.getTypes()).willReturn(List.of(new InquiryTypeCatalogResponse("COUPANG",
                List.of(new InquiryTypeCatalogResponse.Option(InquiryType.PRODUCT_QNA, "상품문의"),
                        new InquiryTypeCatalogResponse.Option(InquiryType.CALL_CENTER, "고객센터문의")))));

        mockMvc.perform(get("/api/inquiries/types").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].platform").value("COUPANG"))
                .andExpect(jsonPath("$.data[0].types[0].code").value("PRODUCT_QNA"))
                .andExpect(jsonPath("$.data[0].types[0].label").value("상품문의"));
    }

    @Test
    void getInquiry_unknownId_returns404() throws Exception {
        given(inquiryQueryService.getInquiry(999L)).willThrow(new ResourceNotFoundException("Inquiry", 999L));

        mockMvc.perform(get("/api/inquiries/999").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getInquiries_requiresAuth() throws Exception {
        mockMvc.perform(get("/api/inquiries"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getInquiry_requiresAuth() throws Exception {
        mockMvc.perform(get("/api/inquiries/1"))
                .andExpect(status().isUnauthorized());
    }

    private CustomerInquiryResponse sample() {
        return new CustomerInquiryResponse(1L, "COUPANG", 7L, "쿠팡-메인", 5L, "테스트셀러",
                InquiryType.PRODUCT_QNA, InquiryStatus.UNANSWERED, "NOANSWER",
                "I-1", null, "V-1", "P-1", null, null, "양말", "재입고 문의", null,
                LocalDateTime.of(2026, 9, 1, 10, 0), null, false, null, null, null, null);
    }
}
