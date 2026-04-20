package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class AuthControllerTest extends BaseIntegrationTest {

    @BeforeEach
    public void setUp() {
        registerTestUsers();
    }

    @AfterEach
    public void tearDown() {
        cleanupTestData();
    }

    @Test
    public void testLoginWithValidCredentials() throws Exception {
        String loginJson = objectMapper.writeValueAsString(
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD)
        );

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(loginJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.email").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    public void testLoginWithWrongPassword() throws Exception {
        String loginJson = objectMapper.writeValueAsString(
                Map.of("email", ADMIN_EMAIL, "password", "wrongpassword")
        );

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(loginJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    public void testLoginWithInvalidEmailFormat() throws Exception {
        String loginJson = objectMapper.writeValueAsString(
                Map.of("email", "invalid-email", "password", ADMIN_PASSWORD)
        );

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(loginJson))
                .andExpect(status().isBadRequest());
    }
}
