package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.service.ShipmentConfirmResult;
import com.pms.service.ShipmentConfirmService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ShipmentConfirmController 보안(401/403/200) + 결과 봉투 테스트.
 *
 * ShipmentConfirmService 를 @MockBean 처리해 컨트롤러 테스트가 쿠팡 API·POI 를 타지 않게 한다.
 */
public class ShipmentConfirmControllerTest extends BaseIntegrationTest {

    @MockBean
    private ShipmentConfirmService shipmentConfirmService;

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "carrier.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});
    }

    @Test
    public void testConfirmWithoutToken() throws Exception {
        mockMvc.perform(multipart("/api/admin/shipping-labels/confirm").file(file()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testConfirmWithUserToken() throws Exception {
        mockMvc.perform(multipart("/api/admin/shipping-labels/confirm").file(file())
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testConfirmWithAdminTokenReturnsResult() throws Exception {
        given(shipmentConfirmService.confirm(any())).willReturn(
                new ShipmentConfirmResult(2, 1, List.of("9999"), 2, List.of()));

        mockMvc.perform(multipart("/api/admin/shipping-labels/confirm").file(file())
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.succeeded").value(2))
                .andExpect(jsonPath("$.data.unmatched[0]").value("9999"));
    }
}
