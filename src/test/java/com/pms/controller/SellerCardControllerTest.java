package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Seller;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SellerCardController — ADMIN authority (401/403/200) + register → edit → hide happy path + full-number 400
 * (FEATURE_2610_18 D52 · D91 · D92).
 */
class SellerCardControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;

    private static final String PATH = "/api/admin/seller-cards";

    private Long sellerId;

    @BeforeEach
    void seed() {
        sellerId = sellerRepository.save(Seller.builder()
                .sellerName("카드셀러").businessRegistration("222-33-44444").build()).getId();
    }

    private String body(String alias, String maskedNumber) {
        return "{\"sellerId\":" + sellerId + ",\"alias\":\"" + alias + "\",\"maskedNumber\":\"" + maskedNumber + "\"}";
    }

    @Test
    void list_권한_401_403_200() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    /** D52 · D91: register → edit alias/number → hide; a hidden card leaves the list. */
    @Test
    void testCreateUpdateHide() throws Exception {
        String created = mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("법인카드", "1234-56**-****-3456")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sellerName").value("카드셀러"))
                .andExpect(jsonPath("$.data.maskedNumber").value("1234-56**-****-3456"))
                .andReturn().getResponse().getContentAsString();
        long cardId = objectMapper.readTree(created).get("data").get("id").asLong();

        mockMvc.perform(put(PATH + "/" + cardId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"개인카드\",\"maskedNumber\":\"9876-****-****-1111\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alias").value("개인카드"))
                .andExpect(jsonPath("$.data.sellerId").value(sellerId));

        mockMvc.perform(delete(PATH + "/" + cardId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /** D92 · D99 ②: a full number (hyphenated) → 400 with the screen text. */
    @Test
    void testCreateFullNumberIsRejected() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("법인카드", "1234-5678-9012-3456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("가려진 번호만 넣으세요"));
    }
}
