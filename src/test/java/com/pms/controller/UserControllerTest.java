package com.pms.controller;

import com.pms.common.BaseIntegrationTest;
import com.pms.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class UserControllerTest extends BaseIntegrationTest {

    private Long testUserId;

    @BeforeEach
    public void setUp() throws Exception {
        testUserId = userRepository.findByEmail(USER_EMAIL).orElseThrow().getId();
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
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.email").value("newuser@test.com"))
                .andExpect(jsonPath("$.data.role").value("GUEST"));
    }

    @Test
    public void testRegisterWithDuplicateEmail() throws Exception {
        String registerJson = objectMapper.writeValueAsString(
                Map.of(
                        "email", USER_EMAIL,
                        "password", "testpass123",
                        "name", "Duplicate User"
                )
        );

        mockMvc.perform(post("/api/users")
                .contentType("application/json")
                .content(registerJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILURE"));
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
    public void testGetUserWithValidUserToken() throws Exception {
        mockMvc.perform(get("/api/users/{id}", testUserId)
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.email").value(USER_EMAIL))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());
    }

    @Test
    public void testGetUserWithInvalidId() throws Exception {
        mockMvc.perform(get("/api/users/{id}", 9999)
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testUpdateOwnUser() throws Exception {
        String updateJson = objectMapper.writeValueAsString(
                Map.of("name", "Updated User")
        );

        mockMvc.perform(patch("/api/users/{id}", testUserId)
                .header("Authorization", "Bearer " + userToken)
                .contentType("application/json")
                .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("Updated User"));
    }

    @Test
    public void testDeleteUser() throws Exception {
        mockMvc.perform(delete("/api/users/{id}", testUserId)
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    public void testUpdateRole() throws Exception {
        String updateJson = objectMapper.writeValueAsString(
                Map.of("role", Role.ADMIN)
        );

        mockMvc.perform(patch("/api/users/{id}/role", testUserId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    // New security-related tests

    @Test
    public void testGetUserWithoutToken() throws Exception {
        mockMvc.perform(get("/api/users/{id}", testUserId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    public void testGetUserWithAdminToken() throws Exception {
        mockMvc.perform(get("/api/users/{id}", testUserId)
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.email").value(USER_EMAIL));
    }

    @Test
    public void testUpdateUserWithoutToken() throws Exception {
        String updateJson = objectMapper.writeValueAsString(
                Map.of("name", "Updated User")
        );

        mockMvc.perform(patch("/api/users/{id}", testUserId)
                .contentType("application/json")
                .content(updateJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testDeleteUserWithoutToken() throws Exception {
        mockMvc.perform(delete("/api/users/{id}", testUserId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testUpdateRoleWithoutToken() throws Exception {
        String updateJson = objectMapper.writeValueAsString(
                Map.of("role", Role.ADMIN)
        );

        mockMvc.perform(patch("/api/users/{id}/role", testUserId)
                .contentType("application/json")
                .content(updateJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"));
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
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    public void testListUsersWithValidToken() throws Exception {
        mockMvc.perform(get("/api/users")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").isNumber())
                .andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    public void testListUsersWithPagination() throws Exception {
        mockMvc.perform(get("/api/users?page=0&size=10")
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.size").value(10));
    }

    @Test
    public void testListUsersWithSearch() throws Exception {
        mockMvc.perform(get("/api/users?search=" + USER_EMAIL)
                .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    public void testListUsersWithoutToken() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    public void testCheckEmailExists() throws Exception {
        mockMvc.perform(get("/api/users/check-email?email=" + USER_EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.exists").value(true));
    }

    @Test
    public void testCheckEmailNotExists() throws Exception {
        mockMvc.perform(get("/api/users/check-email?email=nonexistent@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.exists").value(false));
    }
}
