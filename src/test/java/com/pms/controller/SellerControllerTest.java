package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Seller;
import com.pms.repository.SellerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Seller ADMIN authority for the 옵션확인 suffix PUT (FEATURE_2608_06 / 69): replace semantics (blank suffix →
 * null inherit) + DB reflection + 401/403/404. Same global {@code /api/admin/**} handler.
 */
class SellerControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;

    private static final String PATH = "/api/admin/seller";
    private Long sellerId;

    @BeforeEach
    void seed() {
        sellerId = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build()).getId();
    }

    private String suffixBody(Object enabled, Object suffix) throws Exception {
        if (enabled == null) {
            return objectMapper.writeValueAsString(Collections.singletonMap("suffix", suffix));
        }
        return objectMapper.writeValueAsString(Map.of("enabled", enabled, "suffix", suffix));
    }

    @Test
    void updateRegistrationNameSuffix_noToken_returns401() throws Exception {
        mockMvc.perform(put(PATH + "/" + sellerId + "/registration-name-suffix")
                        .contentType("application/json").content(suffixBody(true, "옵션확인")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateRegistrationNameSuffix_userToken_returns403() throws Exception {
        mockMvc.perform(put(PATH + "/" + sellerId + "/registration-name-suffix")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(suffixBody(true, "옵션확인")))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateRegistrationNameSuffix_adminToken_savesReplaceValues_blankToNull() throws Exception {
        mockMvc.perform(put(PATH + "/" + sellerId + "/registration-name-suffix")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(suffixBody(false, "옵션참고")))
                .andExpect(status().isOk());
        Seller saved = sellerRepository.findById(sellerId).orElseThrow();
        assertThat(saved.getOptionCheckSuffixEnabled()).isFalse();
        assertThat(saved.getOptionCheckSuffix()).isEqualTo("옵션참고");

        // Replace with a blank suffix (enabled omitted) → suffix normalized to null (inherit).
        mockMvc.perform(put(PATH + "/" + sellerId + "/registration-name-suffix")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(suffixBody(null, "   ")))
                .andExpect(status().isOk());
        assertThat(sellerRepository.findById(sellerId).orElseThrow().getOptionCheckSuffix()).isNull();
    }

    @Test
    void updateRegistrationNameSuffix_missingSeller_returns400() throws Exception {
        // SellerServiceImpl's existing convention: a missing seller throws IllegalArgumentException → 400
        // (GlobalExceptionHandler), unlike the account/master paths (ResourceNotFoundException → 404).
        mockMvc.perform(put(PATH + "/999999/registration-name-suffix")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(suffixBody(true, "옵션확인")))
                .andExpect(status().isBadRequest());
    }
}
