package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.PurchasePlace;
import com.pms.repository.PurchasePlaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Purchase place endpoints (FEATURE_2609_76): the read/write authority split (D14), and the two behaviours
 * a mock cannot prove — a rename shows on the product (D3) and an in-use place cannot be deleted (D9).
 *
 * <p>🔴 These tests create products with links. They rely on the class-level {@code @Transactional} rollback of
 * {@link BaseIntegrationTest} — do not add a {@code productRepository.deleteAll()} teardown here: the link rows
 * reference the products and the delete would fail on the foreign key.</p>
 */
class PurchasePlaceControllerTest extends BaseIntegrationTest {

    private static final String PATH = "/api/admin/purchase-places";

    @Autowired private PurchasePlaceRepository purchasePlaceRepository;

    private Long emartId;

    @BeforeEach
    void seedPlace() {
        emartId = purchasePlaceRepository.save(PurchasePlace.builder().name("이마트").sortOrder(0).build()).getId();
    }

    private String nameJson(String name) throws Exception {
        return objectMapper.writeValueAsString(Map.of("name", name));
    }

    private Long createProductWith(Long placeId) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("productName", "신라면", "purchasePlaceIds", List.of(placeId)));
        MvcResult result = mockMvc.perform(post("/api/products").header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.purchasePlaces[0].name").value("이마트"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    @Test
    void list_noToken_returns401() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    /** D14 — the product forms of a non-admin user read this list. */
    @Test
    void list_userToken_returns200() throws Exception {
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("이마트"));
    }

    @Test
    void create_userToken_returns403() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(nameJson("동네마트")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rename_userToken_returns403() throws Exception {
        mockMvc.perform(put(PATH + "/" + emartId).header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(nameJson("이마트24")))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_userToken_returns403() throws Exception {
        mockMvc.perform(delete(PATH + "/" + emartId).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_adminToken_returns200WithNextSortOrder() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(nameJson("동네마트")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("동네마트"))
                .andExpect(jsonPath("$.data.sortOrder").value(1))
                .andExpect(jsonPath("$.data.productCount").value(0));
    }

    /** D3 — the product holds the id, so a rename shows on it with no product write. */
    @Test
    void rename_isVisibleOnProduct() throws Exception {
        Long productId = createProductWith(emartId);

        mockMvc.perform(put(PATH + "/" + emartId).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(nameJson("이마트 트레이더스")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/products/" + productId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.purchasePlaces[0].id").value(emartId))
                .andExpect(jsonPath("$.data.purchasePlaces[0].name").value("이마트 트레이더스"));
    }

    /** D9 — refused with the product count while an active product uses the place. */
    @Test
    void delete_inUse_returns400WithCount() throws Exception {
        createProductWith(emartId);

        mockMvc.perform(delete(PATH + "/" + emartId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("1개 물품이 사용 중입니다"));
    }
}
