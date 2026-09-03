package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.service.CarrierCodeService;
import com.pms.service.CarrierOption;
import com.pms.service.ManualShipmentResult;
import com.pms.service.ShipmentConfirmResult;
import com.pms.service.ShipmentConfirmService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ShipmentConfirmController 보안(401/403/200) + 결과 봉투 테스트.
 *
 * ShipmentConfirmService 를 @MockBean 처리해 컨트롤러 테스트가 쿠팡 API·POI 를 타지 않게 한다.
 * CarrierCodeService 도 @MockBean — 실제 빈이면 H2 에 platform_carrier_code 행이 없어
 * 택배사 목록이 항상 비어 200 테스트가 무의미해진다.
 */
public class ShipmentConfirmControllerTest extends BaseIntegrationTest {

    @MockBean
    private ShipmentConfirmService shipmentConfirmService;

    @MockBean
    private CarrierCodeService carrierCodeService;

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
                new ShipmentConfirmResult(2, 1, List.of("9999"), 2, List.of(), List.of()));

        mockMvc.perform(multipart("/api/admin/shipping-labels/confirm").file(file())
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.succeeded").value(2))
                .andExpect(jsonPath("$.data.unmatched[0]").value("9999"));
    }

    // ---------- 단건 발송처리 (PLAN 2609_11) ----------

    @Test
    public void testCarrierOptionsWithoutToken() throws Exception {
        mockMvc.perform(get("/api/admin/shipping-labels/carrier-options").param("platform", "COUPANG"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testCarrierOptionsWithUserToken() throws Exception {
        mockMvc.perform(get("/api/admin/shipping-labels/carrier-options").param("platform", "COUPANG")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testCarrierOptionsWithAdminTokenReturnsOptions() throws Exception {
        given(carrierCodeService.findOptions("COUPANG"))
                .willReturn(List.of(new CarrierOption("CJGLS", "CJ대한통운", true)));

        mockMvc.perform(get("/api/admin/shipping-labels/carrier-options").param("platform", "COUPANG")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].deliveryCompanyCode").value("CJGLS"))
                .andExpect(jsonPath("$.data[0].carrierName").value("CJ대한통운"))
                .andExpect(jsonPath("$.data[0].registered").value(true));
    }

    @Test
    public void testConfirmManualWithUserToken() throws Exception {
        mockMvc.perform(post("/api/admin/shipping-labels/confirm/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(manualBody("123456789"))
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testConfirmManualWithBlankInvoiceNumberReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/admin/shipping-labels/confirm/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(manualBody(""))
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("송장번호")));
    }

    @Test
    public void testConfirmManualWithAdminTokenReturnsResult() throws Exception {
        given(shipmentConfirmService.confirmManual(any())).willReturn(
                new ManualShipmentResult("4000019469460", "302012345678", "CREATE", 2, 2,
                        List.of(), "DEPARTURE"));

        mockMvc.perform(post("/api/admin/shipping-labels/confirm/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(manualBody("123456789"))
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.orderId").value("4000019469460"))
                .andExpect(jsonPath("$.data.shipmentBoxId").value("302012345678"))
                .andExpect(jsonPath("$.data.mode").value("CREATE"))
                .andExpect(jsonPath("$.data.sentLines").value(2))
                .andExpect(jsonPath("$.data.succeeded").value(2))
                .andExpect(jsonPath("$.data.resultStatus").value("DEPARTURE"));
    }

    private String manualBody(String invoiceNumber) {
        return "{\"orderItemId\":1,\"deliveryCompanyCode\":\"CJGLS\",\"invoiceNumber\":\"" + invoiceNumber + "\"}";
    }
}
