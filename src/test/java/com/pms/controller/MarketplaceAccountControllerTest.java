package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.BackgroundMode;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.Seller;
import com.pms.domain.ThumbnailTemplate;
import com.pms.repository.MarketplaceAccountRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ThumbnailTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MarketplaceAccount ADMIN authority + channel template assignment (FEATURE_2608_06 / 21).
 * Same global {@code /api/admin/**} handler → 401/403 verified once (POST) per CLAUDE CUT guidance.
 */
class MarketplaceAccountControllerTest extends BaseIntegrationTest {

    @Autowired private SellerRepository sellerRepository;
    @Autowired private ThumbnailTemplateRepository thumbnailTemplateRepository;
    @Autowired private MarketplaceAccountRepository accountRepository;

    private static final String PATH = "/api/admin/marketplace-account";
    private Long sellerId;
    private Long templateId;

    @BeforeEach
    void seed() {
        sellerId = sellerRepository.save(Seller.builder()
                .sellerName("행복상회").businessRegistration("111-22-33333").build()).getId();
        templateId = thumbnailTemplateRepository.saveAndFlush(ThumbnailTemplate.builder()
                .name("기본").canvasWidth(1000).canvasHeight(1000)
                .backgroundMode(BackgroundMode.WHITE).active(true).isDefault(true).build()).getId();
    }

    private String body(Long thumbnailTemplateId) throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("sellerId", sellerId);
        map.put("platform", "COUPANG");
        map.put("vendorId", "A00012345");
        map.put("accessKey", "ak");
        map.put("secretKey", "sk");
        if (thumbnailTemplateId != null) {
            map.put("thumbnailTemplateId", thumbnailTemplateId);
        }
        return objectMapper.writeValueAsString(map);
    }

    @Test
    void create_noToken_returns401() throws Exception {
        mockMvc.perform(post(PATH).contentType("application/json").content(body(templateId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_userToken_returns403() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + userToken)
                        .contentType("application/json").content(body(templateId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_adminToken_withTemplateId_returns201_exposesIdNotSecret() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body(templateId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.thumbnailTemplateId").value(templateId))
                .andExpect(jsonPath("$.data.secretKey").doesNotExist());
    }

    @Test
    void create_adminToken_missingTemplateId_returns404() throws Exception {
        mockMvc.perform(post(PATH).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(body(999999L)))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_adminToken_assignsTemplate_returns200() throws Exception {
        Long accountId = accountRepository.saveAndFlush(MarketplaceAccount.builder()
                .seller(sellerRepository.findById(sellerId).orElseThrow())
                .platform("COUPANG").vendorId("A00012345").accessKey("ak").secretKey("sk")
                .isActive(true).build()).getId();

        // secretKey omitted (blank keeps existing); thumbnailTemplateId assigns the template.
        String patchBody = objectMapper.writeValueAsString(Map.of(
                "sellerId", sellerId, "platform", "COUPANG", "vendorId", "A00012345",
                "accessKey", "ak", "thumbnailTemplateId", templateId));

        mockMvc.perform(patch(PATH + "/" + accountId).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thumbnailTemplateId").value(templateId));
    }

    // 69: channel-level 옵션확인 suffix PUT (replace; blank suffix → null inherit). 401/403 already covered above.
    @Test
    void updateRegistrationNameSuffix_adminToken_savesReplaceValues_blankToNull() throws Exception {
        Long accountId = accountRepository.saveAndFlush(MarketplaceAccount.builder()
                .seller(sellerRepository.findById(sellerId).orElseThrow())
                .platform("COUPANG").vendorId("A00012345").accessKey("ak").secretKey("sk")
                .isActive(true).build()).getId();

        mockMvc.perform(put(PATH + "/" + accountId + "/registration-name-suffix")
                        .header("Authorization", "Bearer " + adminToken).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false, "suffix", "옵션참고"))))
                .andExpect(status().isOk());
        MarketplaceAccount saved = accountRepository.findById(accountId).orElseThrow();
        assertThat(saved.getOptionCheckSuffixEnabled()).isFalse();
        assertThat(saved.getOptionCheckSuffix()).isEqualTo("옵션참고");

        // Replace with a blank suffix (enabled omitted) → suffix normalized to null (inherit).
        mockMvc.perform(put(PATH + "/" + accountId + "/registration-name-suffix")
                        .header("Authorization", "Bearer " + adminToken).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Collections.singletonMap("suffix", "   "))))
                .andExpect(status().isOk());
        assertThat(accountRepository.findById(accountId).orElseThrow().getOptionCheckSuffix()).isNull();
    }

    @Test
    void updateRegistrationNameSuffix_missingAccount_returns404() throws Exception {
        mockMvc.perform(put(PATH + "/999999/registration-name-suffix")
                        .header("Authorization", "Bearer " + adminToken).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true, "suffix", "옵션확인"))))
                .andExpect(status().isNotFound());
    }
}
