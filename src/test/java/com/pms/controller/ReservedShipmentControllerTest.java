package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.dto.response.ReservationCreateResult;
import com.pms.service.reservation.ReservedShipmentService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ReservedShipmentController 보안(401/403/200) + 검증(400) 테스트. ReservedShipmentService 를 @MockBean 처리해 쿠팡 API·POI 를 타지 않게 한다. */
public class ReservedShipmentControllerTest extends BaseIntegrationTest {

    @MockBean
    private ReservedShipmentService service;

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "carrier.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});
    }

    @Test
    public void testCreateWithoutToken() throws Exception {
        mockMvc.perform(multipart("/api/admin/reserved-shipments").file(file()).param("executeAt", "2099-01-01T00:02:00"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testCreateWithUserToken() throws Exception {
        mockMvc.perform(multipart("/api/admin/reserved-shipments").file(file()).param("executeAt", "2099-01-01T00:02:00")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testListWithoutToken() throws Exception {
        mockMvc.perform(get("/api/admin/reserved-shipments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testListWithUserToken() throws Exception {
        mockMvc.perform(get("/api/admin/reserved-shipments")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testListByOrderWithoutToken() throws Exception {
        mockMvc.perform(get("/api/admin/reserved-shipments/orders/4000019469460"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testListByOrderWithUserToken() throws Exception {
        mockMvc.perform(get("/api/admin/reserved-shipments/orders/4000019469460")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testCancelItemsWithoutToken() throws Exception {
        mockMvc.perform(post("/api/admin/reserved-shipments/items/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"orderItemIds\":[1]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testCancelItemsWithUserToken() throws Exception {
        mockMvc.perform(post("/api/admin/reserved-shipments/items/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"orderItemIds\":[1]}")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testChangeExecuteAtWithoutToken() throws Exception {
        mockMvc.perform(patch("/api/admin/reserved-shipments/items/100/execute-at").contentType(MediaType.APPLICATION_JSON).content("{\"executeAt\":\"2099-01-01T00:02:00\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testChangeExecuteAtWithUserToken() throws Exception {
        mockMvc.perform(patch("/api/admin/reserved-shipments/items/100/execute-at").contentType(MediaType.APPLICATION_JSON).content("{\"executeAt\":\"2099-01-01T00:02:00\"}")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testRetryWithoutToken() throws Exception {
        mockMvc.perform(post("/api/admin/reserved-shipments/items/100/retry"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRetryWithUserToken() throws Exception {
        mockMvc.perform(post("/api/admin/reserved-shipments/items/100/retry")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testInvoicesOfOrderWithoutToken() throws Exception {
        mockMvc.perform(get("/api/admin/reserved-shipments/orders/4000019469460/invoices"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testInvoicesOfOrderWithUserToken() throws Exception {
        mockMvc.perform(get("/api/admin/reserved-shipments/orders/4000019469460/invoices")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testChangeInvoiceWithoutToken() throws Exception {
        mockMvc.perform(put("/api/admin/reserved-shipments/shipments/10/invoice").contentType(MediaType.APPLICATION_JSON).content("{\"deliveryCompanyCode\":\"CJGLS\",\"invoiceNumber\":\"111\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testChangeInvoiceWithUserToken() throws Exception {
        mockMvc.perform(put("/api/admin/reserved-shipments/shipments/10/invoice").contentType(MediaType.APPLICATION_JSON).content("{\"deliveryCompanyCode\":\"CJGLS\",\"invoiceNumber\":\"111\"}")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testReserveStoredWithoutToken() throws Exception {
        mockMvc.perform(post("/api/admin/reserved-shipments/stored").contentType(MediaType.APPLICATION_JSON).content("{\"orderItemIds\":[1],\"executeAt\":\"2099-01-01T00:02:00\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testReserveStoredWithUserToken() throws Exception {
        mockMvc.perform(post("/api/admin/reserved-shipments/stored").contentType(MediaType.APPLICATION_JSON).content("{\"orderItemIds\":[1],\"executeAt\":\"2099-01-01T00:02:00\"}")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testShipStoredNowWithoutToken() throws Exception {
        mockMvc.perform(post("/api/admin/reserved-shipments/stored/ship-now").contentType(MediaType.APPLICATION_JSON).content("{\"orderItemIds\":[1]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testShipStoredNowWithUserToken() throws Exception {
        mockMvc.perform(post("/api/admin/reserved-shipments/stored/ship-now").contentType(MediaType.APPLICATION_JSON).content("{\"orderItemIds\":[1]}")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testCreateWithAdminTokenReturnsResult() throws Exception {
        given(service.create(any(), any())).willReturn(
                new ReservationCreateResult(9L, LocalDateTime.of(2099, 1, 1, 0, 2), 1, 0, List.of()));

        mockMvc.perform(multipart("/api/admin/reserved-shipments").file(file()).param("executeAt", "2099-01-01T00:02:00")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationId").value(9));
    }

    @Test
    public void testListWithAdminTokenReturnsArray() throws Exception {
        given(service.list()).willReturn(List.of());

        mockMvc.perform(get("/api/admin/reserved-shipments")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    public void testListByOrderWithAdminTokenReturnsArray() throws Exception {
        given(service.listByOrder("4000019469460")).willReturn(List.of());

        mockMvc.perform(get("/api/admin/reserved-shipments/orders/4000019469460")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    public void testChangeInvoiceWithBlankInvoiceReturnsBadRequest() throws Exception {
        mockMvc.perform(put("/api/admin/reserved-shipments/shipments/10/invoice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deliveryCompanyCode\":\"CJGLS\",\"invoiceNumber\":\"\"}")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("송장번호는 필수입니다")));
    }
}
