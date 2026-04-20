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
    public void testGetUserWithValidToken() throws Exception {
        mockMvc.perform(get("/api/users/{id}", testUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.email").value(USER_EMAIL))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());
    }

    @Test
    public void testGetUserWithInvalidId() throws Exception {
        mockMvc.perform(get("/api/users/{id}", 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"));
    }

    @Test
    public void testUpdateOwnUser() throws Exception {
        String updateJson = objectMapper.writeValueAsString(
                Map.of("name", "Updated User")
        );

        mockMvc.perform(patch("/api/users/{id}", testUserId)
                .contentType("application/json")
                .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("Updated User"));
    }

    @Test
    public void testDeleteUser() throws Exception {
        mockMvc.perform(delete("/api/users/{id}", testUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    public void testUpdateRole() throws Exception {
        String updateJson = objectMapper.writeValueAsString(
                Map.of("role", Role.ADMIN)
        );

        mockMvc.perform(patch("/api/users/{id}/role", testUserId)
                .contentType("application/json")
                .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }
}
