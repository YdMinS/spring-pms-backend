package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.dto.response.OrderSettingResponse;
import com.pms.service.reservation.OrderSettingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OrderSettingController 보안(401/403/200) + 검증(400) 테스트. 서비스는 @MockBean. */
public class OrderSettingControllerTest extends BaseIntegrationTest {

    @MockBean
    private OrderSettingService orderSettingService;

    @Test
    public void testGetOrderSettingWithoutToken() throws Exception {
        mockMvc.perform(get("/api/admin/order-settings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testGetOrderSettingWithUserToken() throws Exception {
        mockMvc.perform(get("/api/admin/order-settings")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testGetOrderSettingWithAdminTokenReturnsSetting() throws Exception {
        given(orderSettingService.get())
                .willReturn(new OrderSettingResponse("00:02", LocalDateTime.of(2026, 9, 29, 0, 2)));

        mockMvc.perform(get("/api/admin/order-settings")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservedShipmentTime").value("00:02"));
    }

    @Test
    public void testUpdateOrderSettingWithoutToken() throws Exception {
        mockMvc.perform(put("/api/admin/order-settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reservedShipmentTime\":\"00:02\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testUpdateOrderSettingWithUserToken() throws Exception {
        mockMvc.perform(put("/api/admin/order-settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reservedShipmentTime\":\"00:02\"}")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testUpdateOrderSettingWithBlankTimeReturnsBadRequest() throws Exception {
        mockMvc.perform(put("/api/admin/order-settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reservedShipmentTime\":\"\"}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("예약 시각을 입력하세요")));
    }
}
