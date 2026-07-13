package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Carrier;
import com.pms.domain.PlatformCarrierCode;
import com.pms.repository.PlatformCarrierCodeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class PlatformCarrierCodeControllerTest extends BaseIntegrationTest {

    @Autowired
    private PlatformCarrierCodeRepository platformCarrierCodeRepository;

    private Long carrierId;

    @BeforeEach
    public void setUp() {
        Carrier carrier = carrierRepository.save(
                Carrier.builder().name("CJ대한통운").isActive(true).build());
        carrierId = carrier.getId();
    }

    @AfterEach
    public void cleanUpCodes() {
        // 자식 플랫폼 코드를 먼저 지워야 부모 cleanup(carrier deleteAll)이 FK 위반하지 않음.
        platformCarrierCodeRepository.deleteAll();
    }

    private PlatformCarrierCode saveCode(String platform, String code) {
        return platformCarrierCodeRepository.save(PlatformCarrierCode.builder()
                .carrier(carrierRepository.findById(carrierId).orElseThrow())
                .platform(platform)
                .deliveryCompanyCode(code)
                .build());
    }

    @Test
    public void create_asAdmin_201() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of("platform", "COUPANG", "deliveryCompanyCode", "CJGLS"));

        mockMvc.perform(post("/api/admin/carriers/" + carrierId + "/platform-codes")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.platform").value("COUPANG"))
                .andExpect(jsonPath("$.data.carrierId").value(carrierId));
    }

    @Test
    public void create_asUser_403() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of("platform", "COUPANG", "deliveryCompanyCode", "CJGLS"));

        mockMvc.perform(post("/api/admin/carriers/" + carrierId + "/platform-codes")
                .header("Authorization", "Bearer " + userToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void create_duplicatePlatform_409() throws Exception {
        saveCode("COUPANG", "CJGLS");
        String requestJson = objectMapper.writeValueAsString(
                Map.of("platform", "COUPANG", "deliveryCompanyCode", "HANJIN"));

        mockMvc.perform(post("/api/admin/carriers/" + carrierId + "/platform-codes")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void create_carrierNotFound_404() throws Exception {
        String requestJson = objectMapper.writeValueAsString(
                Map.of("platform", "COUPANG", "deliveryCompanyCode", "CJGLS"));

        mockMvc.perform(post("/api/admin/carriers/999999/platform-codes")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void list_authenticated_200() throws Exception {
        saveCode("COUPANG", "CJGLS");

        // USER token: GET is authenticated (dropdown source).
        mockMvc.perform(get("/api/admin/carriers/" + carrierId + "/platform-codes")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].platform").value("COUPANG"));
    }

    @Test
    public void update_asAdmin_replacesFields_200() throws Exception {
        PlatformCarrierCode code = saveCode("COUPANG", "CJGLS");
        String requestJson = objectMapper.writeValueAsString(
                Map.of("platform", "NAVER", "deliveryCompanyCode", "HANJIN"));

        mockMvc.perform(patch("/api/admin/carriers/" + carrierId + "/platform-codes/" + code.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.platform").value("NAVER"))
                .andExpect(jsonPath("$.data.deliveryCompanyCode").value("HANJIN"));
    }

    @Test
    public void update_duplicatePlatform_409() throws Exception {
        saveCode("COUPANG", "CJGLS");
        PlatformCarrierCode target = saveCode("NAVER", "HANJIN");
        // NAVER 코드를 이미 존재하는 COUPANG 으로 수정 시도 → 409.
        String requestJson = objectMapper.writeValueAsString(
                Map.of("platform", "COUPANG", "deliveryCompanyCode", "HANJIN"));

        mockMvc.perform(patch("/api/admin/carriers/" + carrierId + "/platform-codes/" + target.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(requestJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void delete_asAdmin_200() throws Exception {
        PlatformCarrierCode code = saveCode("COUPANG", "CJGLS");

        mockMvc.perform(delete("/api/admin/carriers/" + carrierId + "/platform-codes/" + code.getId())
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(get("/api/admin/carriers/" + carrierId + "/platform-codes")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
