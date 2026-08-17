package com.pms.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.Product;
import com.pms.domain.Role;
import com.pms.domain.User;
import com.pms.repository.ProductRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves Hibernate {@code @TenantId} isolation for {@link Product} (vertical slice 1).
 *
 * <p>⚠️ Intentionally NOT extending {@code BaseIntegrationTest} and NOT {@code @Transactional}.
 * Hibernate resolves the tenant once at session-open, so a single shared transaction cannot
 * switch tenants mid-test. Here each repository call opens its own session, which reads the
 * live {@link TenantContext}. Product/member cleanup uses native SQL (bypasses the tenant
 * filter → deletes across all tenants).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Product tenant isolation (@TenantId)")
class ProductTenantIsolationTest {

    private static final Long TENANT_1 = 1L;
    private static final Long TENANT_2 = 2L;
    private static final String PASSWORD = "testpass123";

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        // Native SQL bypasses the @TenantId filter → removes rows for every tenant.
        // deleteAll() would only remove the current context's tenant, leaking others.
        refreshTokenRepository.deleteAll();       // FK → member
        jdbcTemplate.execute("delete from products");
        jdbcTemplate.execute("delete from member");
    }

    @Test
    @DisplayName("SELECT is auto-filtered by tenant")
    void selectIsScopedToCurrentTenant() {
        TenantContext.set(TENANT_1);
        productRepository.save(productFixture("A"));
        TenantContext.set(TENANT_2);
        productRepository.save(productFixture("B"));

        TenantContext.set(TENANT_1);
        assertThat(productRepository.findAll())
                .extracting(Product::getProductName)
                .containsExactly("A");   // B not visible = isolation proven

        TenantContext.set(TENANT_2);
        assertThat(productRepository.findAll())
                .extracting(Product::getProductName)
                .containsExactly("B");
    }

    @Test
    @DisplayName("INSERT auto-sets tenant_id from resolver")
    void insertAutoSetsTenantId() {
        TenantContext.set(TENANT_2);
        Long id = productRepository.save(productFixture("only-t2")).getId();

        // Native read bypasses the filter and confirms the stored discriminator.
        Long storedTenantId = jdbcTemplate.queryForObject(
                "select tenant_id from products where id = ?", Long.class, id);
        assertThat(storedTenantId).isEqualTo(TENANT_2);

        // Not visible from another tenant's context (query-level filter; PK find() is not
        // tenant-filtered by Hibernate, so isolation is asserted via findAll).
        TenantContext.set(TENANT_1);
        assertThat(productRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("API listing scoped by JWT tenantId claim (filter → resolver pipeline)")
    void apiListingIsScopedByTokenTenant() throws Exception {
        seedUser("t1@test.com", TENANT_1);
        seedUser("t2@test.com", TENANT_2);

        TenantContext.set(TENANT_1);
        productRepository.save(productFixture("P1"));
        TenantContext.set(TENANT_2);
        productRepository.save(productFixture("P2"));
        TenantContext.clear();

        String tenant1Token = login("t1@test.com");

        String body = mockMvc.perform(get("/api/products")
                        .header("Authorization", "Bearer " + tenant1Token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode content = objectMapper.readTree(body).get("data").get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("productName").asText()).isEqualTo("P1");
    }

    // ==================== helpers ====================

    private Product productFixture(String name) {
        return Product.builder()
                .productName(name)
                .active(true)
                .build();
    }

    private void seedUser(String email, Long tenantId) {
        // member is not @TenantId (phase 03); tenant_id is a plain column set explicitly here.
        userRepository.save(User.builder()
                .tenantId(tenantId)
                .email(email)
                .password(passwordEncoder.encode(PASSWORD))
                .name(email)
                .role(Role.ADMIN)
                .build());
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
