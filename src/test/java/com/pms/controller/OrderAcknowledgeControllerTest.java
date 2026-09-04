package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.service.OrderAcknowledgeResult;
import com.pms.service.OrderAcknowledgeService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OrderAcknowledgeController 보안(401/403/200) + 검증(400) 테스트.
 *
 * OrderAcknowledgeService 를 @MockBean 처리해 컨트롤러 테스트가 쿠팡 API 를 타지 않게 한다.
 */
public class OrderAcknowledgeControllerTest extends BaseIntegrationTest {

    private static final String PATH = "/api/admin/orders/acknowledge";
    private static final String BODY = "{\"orderItemIds\":[1,2]}";

    @MockBean
    private OrderAcknowledgeService orderAcknowledgeService;

    @Test
    public void testAcknowledgeWithoutToken() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testAcknowledgeWithUserToken() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testAcknowledgeWithAdminTokenReturnsResult() throws Exception {
        given(orderAcknowledgeService.acknowledge(any())).willReturn(
                new OrderAcknowledgeResult(2, 1, 1, List.of(), List.of(), List.of()));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.succeeded").value(1))
                .andExpect(jsonPath("$.data.targetBoxes").value(1));
    }

    @Test
    public void testAcknowledgeWithEmptySelectionReturnsBadRequest() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderItemIds\":[]}")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("주문 라인을 1건 이상 선택하세요")));
    }

    @Test
    public void testAcknowledgeWhenServiceRejectsReturnsBadRequest() throws Exception {
        willThrow(new IllegalArgumentException("주문 라인을 찾을 수 없습니다"))
                .given(orderAcknowledgeService).acknowledge(any());

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("주문 라인을 찾을 수 없습니다")));
    }
}
