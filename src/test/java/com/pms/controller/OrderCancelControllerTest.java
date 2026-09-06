package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.OrderCancelReason;
import com.pms.service.OrderCancelResult;
import com.pms.service.OrderCancelService;
import com.pms.service.claim.ActionChoice;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OrderCancelController 보안(401/403/200) + 검증(400) 테스트 (FEATURE_2609_25).
 *
 * OrderCancelService 를 @MockBean 처리해 컨트롤러 테스트가 쿠팡 API 를 타지 않게 한다 —
 * 취소는 되돌릴 수 없는 호출이라 더더욱 실제 서비스를 태우면 안 된다.
 */
public class OrderCancelControllerTest extends BaseIntegrationTest {

    private static final String CANCEL_PATH = "/api/admin/orders/cancel";
    private static final String REASONS_PATH = "/api/admin/orders/cancel-reasons";
    private static final String BODY =
            "{\"lines\":[{\"orderItemId\":11,\"quantity\":2}],\"reason\":\"OUT_OF_STOCK\"}";

    @MockBean
    private OrderCancelService orderCancelService;

    @Test
    public void testCancelWithAdminTokenReturnsResult() throws Exception {
        given(orderCancelService.cancel(any())).willReturn(new OrderCancelResult(
                1, 1, 2,
                List.of(new OrderCancelResult.CancelledLine(11L, 2, 2, 0, 0, "CANCELLED",
                        "90001", "CANCEL")),
                List.of(), List.of(), List.of()));

        mockMvc.perform(post(CANCEL_PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.succeededLines").value(1))
                .andExpect(jsonPath("$.data.succeededQty").value(2))
                .andExpect(jsonPath("$.data.cancelled[0].resultStatus").value("CANCELLED"));
    }

    @Test
    public void testCancelWithUserToken() throws Exception {
        mockMvc.perform(post(CANCEL_PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testCancelWithoutToken() throws Exception {
        mockMvc.perform(post(CANCEL_PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testCancelWithEmptyLinesReturnsBadRequest() throws Exception {
        mockMvc.perform(post(CANCEL_PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[],\"reason\":\"OUT_OF_STOCK\"}")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testCancelReasonsWithAdminTokenReturnsChoices() throws Exception {
        given(orderCancelService.availableReasons()).willReturn(
                Arrays.stream(OrderCancelReason.values())
                        .map(r -> new ActionChoice(r.name(), r.getLabel()))
                        .toList());

        mockMvc.perform(get(REASONS_PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.data[0].label").value("상품 품절 / 재고 부족"));
    }
}
