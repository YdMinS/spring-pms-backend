package com.pms.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pms.domain.Role;
import com.pms.domain.User;
import com.pms.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class BaseIntegrationTest {

    protected static final String USER_EMAIL = "user@test.com";
    protected static final String USER_PASSWORD = "testpass123";
    protected static final String ADMIN_EMAIL = "admin@test.com";
    protected static final String ADMIN_PASSWORD = "testpass123";

    protected String adminToken;
    protected String userToken;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected BCryptPasswordEncoder passwordEncoder;

    protected void registerTestUsers() {
        User admin = User.builder()
                .email(ADMIN_EMAIL)
                .password(passwordEncoder.encode(ADMIN_PASSWORD))
                .name("Admin User")
                .role(Role.ADMIN)
                .build();
        userRepository.save(admin);

        User user = User.builder()
                .email(USER_EMAIL)
                .password(passwordEncoder.encode(USER_PASSWORD))
                .name("Test User")
                .role(Role.USER)
                .build();
        userRepository.save(user);
    }

    protected String generateAdminToken() throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("token").asText();
    }

    protected String generateUserToken() throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + USER_EMAIL + "\",\"password\":\"" + USER_PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("token").asText();
    }

    protected void cleanupTestData() {
        userRepository.deleteByEmail(ADMIN_EMAIL);
        userRepository.deleteByEmail(USER_EMAIL);
    }

    @BeforeEach
    public void setUpBase() throws Exception {
        registerTestUsers();
        adminToken = generateAdminToken();
        userToken = generateUserToken();
    }

    @AfterEach
    public void tearDownBase() {
        cleanupTestData();
    }
}
