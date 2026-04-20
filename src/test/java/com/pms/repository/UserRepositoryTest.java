package com.pms.repository;

import com.pms.common.TestJpaConfig;
import com.pms.domain.Role;
import com.pms.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@Import(TestJpaConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    private BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private User testUser;

    @BeforeEach
    public void setUp() {
        testUser = User.builder()
                .email("test@example.com")
                .password(passwordEncoder.encode("testpass123"))
                .name("Test User")
                .role(Role.USER)
                .build();
    }

    @Test
    @Order(1)
    public void testFindByEmailWithExistingEmail() {
        userRepository.save(testUser);

        Optional<User> result = userRepository.findByEmail("test@example.com");

        assertTrue(result.isPresent());
        assertEquals("test@example.com", result.get().getEmail());
        assertEquals("Test User", result.get().getName());
    }

    @Test
    @Order(2)
    public void testFindByEmailWithNonExistingEmail() {
        Optional<User> result = userRepository.findByEmail("nonexistent@example.com");

        assertFalse(result.isPresent());
    }

    @Test
    @Order(3)
    public void testExistsByEmailWithExistingEmail() {
        userRepository.save(testUser);

        boolean exists = userRepository.existsByEmail("test@example.com");

        assertTrue(exists);
    }

    @Test
    @Order(4)
    public void testExistsByEmailWithNonExistingEmail() {
        boolean exists = userRepository.existsByEmail("nonexistent@example.com");

        assertFalse(exists);
    }

    @Test
    @Order(5)
    public void testSaveWithValidUser() {
        User savedUser = userRepository.save(testUser);

        assertNotNull(savedUser.getId());
        assertEquals("test@example.com", savedUser.getEmail());
        assertTrue(passwordEncoder.matches("testpass123", savedUser.getPassword()));
    }

    @Test
    @Order(6)
    public void testSaveWithDuplicateEmail() {
        userRepository.save(testUser);

        User duplicateUser = User.builder()
                .email("test@example.com")
                .password(passwordEncoder.encode("anotherpass123"))
                .name("Another User")
                .role(Role.USER)
                .build();

        assertThrows(DataIntegrityViolationException.class, () -> {
            userRepository.saveAndFlush(duplicateUser);
        });
    }

    @Test
    @Order(7)
    public void testAuditFieldsAutoPopulated() {
        User savedUser = userRepository.save(testUser);

        assertNotNull(savedUser.getCreatedAt());
        assertNotNull(savedUser.getUpdatedAt());
        assertTrue(savedUser.getCreatedAt() instanceof LocalDateTime);
        assertTrue(savedUser.getUpdatedAt() instanceof LocalDateTime);
    }
}
