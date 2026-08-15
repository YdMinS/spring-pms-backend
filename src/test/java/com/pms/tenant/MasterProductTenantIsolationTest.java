package com.pms.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.MasterProduct;
import com.pms.domain.Role;
import com.pms.domain.User;
import com.pms.repository.MasterProductRepository;
import com.pms.repository.RefreshTokenRepository;
import com.pms.repository.UserRepository;
import com.pms.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves cross-tenant master products are not exposed (FEATURE_2608_06 / 3a security MUST-KEEP).
 *
 * <p>⚠️ Intentionally NOT {@code @Transactional}: Hibernate resolves the tenant once per session, so a
 * single shared transaction cannot seed one tenant's row and read as another. Here each request opens
 * its own session reading the live {@link TenantContext} (mirrors {@code ProductTenantIsolationTest}).
 * Cleanup uses native SQL to bypass the tenant filter.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("MasterProduct tenant isolation (@TenantId)")
class MasterProductTenantIsolationTest {

    private static final Long TENANT_1 = 1L;
    private static final Long TENANT_2 = 2L;
    private static final String PASSWORD = "testpass123";

    @Autowired private MasterProductRepository masterProductRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BCryptPasswordEncoder passwordEncoder;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        refreshTokenRepository.deleteAll();          // FK → member
        jdbcTemplate.execute("delete from master_product");
        jdbcTemplate.execute("delete from member");
    }

    @Test
    @DisplayName("tenant 1 cannot read a tenant 2 master (matrix + get → 404)")
    void crossTenantMasterIsNotVisible() throws Exception {
        seedAdmin("t1@test.com", TENANT_1);

        TenantContext.set(TENANT_2);
        Long tenant2MasterId = masterProductRepository.save(
                MasterProduct.builder().name("타테넌트마스터").build()).getId();
        TenantContext.clear();

        String tenant1Token = login("t1@test.com");

        mockMvc.perform(get("/api/admin/master-products/" + tenant2MasterId + "/matrix")
                        .header("Authorization", "Bearer " + tenant1Token))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/admin/master-products/" + tenant2MasterId)
                        .header("Authorization", "Bearer " + tenant1Token))
                .andExpect(status().isNotFound());
    }

    private void seedAdmin(String email, Long tenantId) {
        // member is not @TenantId — tenant_id is a plain column set explicitly.
        userRepository.save(User.builder()
                .tenantId(tenantId).email(email).password(passwordEncoder.encode(PASSWORD))
                .name(email).role(Role.ADMIN).build());
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("token").asText();
    }
}
