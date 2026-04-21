package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class AuthControllerTest extends BaseIntegrationTest {

    @Test
    public void testLoginWithValidAdminCredentials() throws Exception {
        String loginJson = objectMapper.writeValueAsString(
                Map.of(
                        "email", ADMIN_EMAIL,
                        "password", ADMIN_PASSWORD
                )
        );

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(loginJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.token").exists())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.email").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.expiresIn").isNumber())
                .andExpect(result -> {
                    String token = objectMapper.readTree(result.getResponse().getContentAsString())
                            .get("data").get("token").asText();
                    assert token.startsWith("eyJ") : "Token should be a valid JWT starting with 'eyJ'";
                })
                .andExpect(result -> {
                    long expiresIn = objectMapper.readTree(result.getResponse().getContentAsString())
                            .get("data").get("expiresIn").asLong();
                    assert expiresIn > System.currentTimeMillis() : "Token should have future expiration";
                });
    }

    @Test
    public void testLoginWithValidUserCredentials() throws Exception {
        String loginJson = objectMapper.writeValueAsString(
                Map.of(
                        "email", USER_EMAIL,
                        "password", USER_PASSWORD
                )
        );

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(loginJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.token").exists())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.email").value(USER_EMAIL))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    public void testLoginWithWrongPassword() throws Exception {
        String loginJson = objectMapper.writeValueAsString(
                Map.of(
                        "email", USER_EMAIL,
                        "password", "wrongpassword"
                )
        );

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(loginJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    public void testLoginWithUserNotFound() throws Exception {
        String loginJson = objectMapper.writeValueAsString(
                Map.of(
                        "email", "nonexistent@test.com",
                        "password", "testpass123"
                )
        );

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(loginJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    public void testLoginWithInvalidEmailFormat() throws Exception {
        String loginJson = objectMapper.writeValueAsString(
                Map.of(
                        "email", "invalidemail",
                        "password", "testpass123"
                )
        );

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(loginJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }
}
