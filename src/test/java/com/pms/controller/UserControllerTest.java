package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class UserControllerTest extends BaseIntegrationTest {

    private Long testAdminId;
    private Long testUserId;
    private String adminToken;
    private String userToken;

    @BeforeEach
    public void setUp() throws Exception {
        registerTestUsers();
        testAdminId = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow().getId();
        testUserId = userRepository.findByEmail(USER_EMAIL).orElseThrow().getId();
        adminToken = generateAdminToken();
        userToken = generateUserToken();
    }

    @AfterEach
    public void tearDown() {
        cleanupTestData();
    }

    @Test
    public void testRegisterWithValidRequest() throws Exception {
        String registerJson = objectMapper.writeValueAsString(
                Map.of(
                        "email", "newuser@test.com",
                        "password", "testpass123",
                        "name", "New User"
                )
        );

        mockMvc.perform(post("/api/users")
                .contentType("application/json")
                .content(registerJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.email").value("newuser@test.com"))
                .andExpect(jsonPath("$.data.role").value("GUEST"));
    }

    @Test
    public void testRegisterWithDuplicateEmail() throws Exception {
        String registerJson = objectMapper.writeValueAsString(
                Map.of(
                        "email", ADMIN_EMAIL,
                        "password", "testpass123",
                        "name", "Duplicate User"
                )
        );

        mockMvc.perform(post("/api/users")
                .contentType("application/json")
                .content(registerJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FAILURE"));
    }

    @Test
    public void testRegisterWithInvalidRequest() throws Exception {
        String registerJson = objectMapper.writeValueAsString(
                Map.of(
                        "email", "invalid",
                        "password", "short",
                        "name", "U"
                )
        );

        mockMvc.perform(post("/api/users")
                .contentType("application/json")
                .content(registerJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testGetUserWithoutToken() throws Exception {
        mockMvc.perform(get("/api/users/{id}", testAdminId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    public void testGetUserWithValidToken() throws Exception {
        mockMvc.perform(get("/api/users/{id}", testAdminId)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.email").value(ADMIN_EMAIL));
    }

    @Test
    public void testGetUserWithInvalidId() throws Exception {
        mockMvc.perform(get("/api/users/{id}", 9999)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FAILURE"));
    }

    @Test
    public void testUpdateOwnUser() throws Exception {
        String updateJson = objectMapper.writeValueAsString(
                Map.of("name", "Updated Admin")
        );

        mockMvc.perform(patch("/api/users/{id}", testAdminId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("Updated Admin"));
    }

    @Test
    public void testDeleteUser() throws Exception {
        mockMvc.perform(delete("/api/users/{id}", testUserId)
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    public void testUpdateRoleWithUserToken() throws Exception {
        String updateJson = objectMapper.writeValueAsString(
                Map.of("role", Role.ADMIN)
        );

        mockMvc.perform(patch("/api/users/{id}/role", testUserId)
                .header("Authorization", "Bearer " + userToken)
                .contentType("application/json")
                .content(updateJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    public void testUpdateRoleWithAdminToken() throws Exception {
        String updateJson = objectMapper.writeValueAsString(
                Map.of("role", Role.ADMIN)
        );

        mockMvc.perform(patch("/api/users/{id}/role", testUserId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }
}
