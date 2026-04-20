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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class BaseIntegrationTest {

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
        User user = User.builder()
                .email(USER_EMAIL)
                .password(passwordEncoder.encode(USER_PASSWORD))
                .name("Test User")
                .role(Role.USER)
                .build();
        userRepository.save(user);
    }

    protected void cleanupTestData() {
        userRepository.deleteByEmail(USER_EMAIL);
    }

    @BeforeEach
    public void setUpBase() {
        registerTestUsers();
    }

    @AfterEach
    public void tearDownBase() {
        cleanupTestData();
    }
}
