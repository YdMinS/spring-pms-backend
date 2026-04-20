package com.pms.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.Role;
import com.pms.domain.User;
import com.pms.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    protected static final String ADMIN_EMAIL = "admin@test.com";
    protected static final String ADMIN_PASSWORD = "testpass123";
    protected static final String USER_EMAIL = "user@test.com";
    protected static final String USER_PASSWORD = "testpass123";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected BCryptPasswordEncoder passwordEncoder;

    protected void registerTestUsers() {
        // Step 1: Create ADMIN user directly via UserRepository
        User adminUser = User.builder()
                .email(ADMIN_EMAIL)
                .password(passwordEncoder.encode(ADMIN_PASSWORD))
                .name("Admin User")
                .role(Role.ADMIN)
                .build();
        userRepository.save(adminUser);

        // Step 2: Create USER user directly via UserRepository
        User normalUser = User.builder()
                .email(USER_EMAIL)
                .password(passwordEncoder.encode(USER_PASSWORD))
                .name("User User")
                .role(Role.USER)
                .build();
        userRepository.save(normalUser);
    }

    protected String generateAdminToken() throws Exception {
        String loginJson = objectMapper.writeValueAsString(
                java.util.Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD)
        );

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(loginJson))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(responseBody);
        return jsonNode.get("data").get("token").asText();
    }

    protected String generateUserToken() throws Exception {
        String loginJson = objectMapper.writeValueAsString(
                java.util.Map.of("email", USER_EMAIL, "password", USER_PASSWORD)
        );

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(loginJson))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(responseBody);
        return jsonNode.get("data").get("token").asText();
    }

    protected void cleanupTestData() {
        userRepository.findByEmail(ADMIN_EMAIL).ifPresent(userRepository::delete);
        userRepository.findByEmail(USER_EMAIL).ifPresent(userRepository::delete);
    }
}
