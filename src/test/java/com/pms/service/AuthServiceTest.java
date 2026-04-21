package com.pms.service;

import com.pms.domain.Role;
import com.pms.domain.User;
import com.pms.dto.request.LoginRequest;
import com.pms.dto.response.AuthResponse;
import com.pms.exception.UnauthorizedException;
import com.pms.repository.UserRepository;
import com.pms.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private AuthServiceImpl authService;

    private User testUser;
    private LoginRequest validRequest;
    private LoginRequest invalidPasswordRequest;
    private LoginRequest userNotFoundRequest;

    @BeforeEach
    public void setUp() {
        testUser = User.builder()
                .id(1L)
                .email("user@test.com")
                .password("encodedPassword")
                .name("Test User")
                .role(Role.USER)
                .build();

        validRequest = LoginRequest.builder()
                .email("user@test.com")
                .password("testpass123")
                .build();

        invalidPasswordRequest = LoginRequest.builder()
                .email("user@test.com")
                .password("wrongpassword")
                .build();

        userNotFoundRequest = LoginRequest.builder()
                .email("nonexistent@test.com")
                .password("testpass123")
                .build();
    }

    @Test
    public void testLoginWithValidCredentials() {
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("testpass123", "encodedPassword")).thenReturn(true);
        when(jwtTokenProvider.generateToken(org.mockito.ArgumentMatchers.any())).thenReturn("test.jwt.token");
        when(jwtTokenProvider.getExpirationTime()).thenReturn(3600000L);

        AuthResponse response = authService.login(validRequest);

        assertNotNull(response);
        assertNotNull(response.getToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals("user@test.com", response.getEmail());
        assertEquals("Test User", response.getName());
        assertEquals(Role.USER, response.getRole());
        assertTrue(response.getExpiresIn() > System.currentTimeMillis());
    }

    @Test
    public void testLoginWithWrongPassword() {
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrongpassword", "encodedPassword")).thenReturn(false);

        assertThrows(UnauthorizedException.class, () -> authService.login(invalidPasswordRequest));
    }

    @Test
    public void testLoginWithUserNotFound() {
        when(userRepository.findByEmail("nonexistent@test.com")).thenReturn(Optional.empty());

        assertThrows(UnauthorizedException.class, () -> authService.login(userNotFoundRequest));
    }
}
