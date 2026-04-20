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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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

    @BeforeEach
    public void setUp() {
        testUser = User.builder()
                .id(1L)
                .email("test@example.com")
                .password("encodedPassword")
                .name("Test User")
                .role(Role.USER)
                .build();

        validRequest = LoginRequest.builder()
                .email("test@example.com")
                .password("testpass123")
                .build();
    }

    @Test
    public void testLoginWithValidCredentials() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("testpass123", "encodedPassword")).thenReturn(true);
        when(jwtTokenProvider.generateToken(any())).thenReturn("test-token");

        AuthResponse response = authService.login(validRequest);

        assertNotNull(response);
        assertEquals("test-token", response.getToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals("test@example.com", response.getEmail());
        assertEquals(Role.USER, response.getRole());
    }

    @Test
    public void testLoginWithWrongPassword() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("testpass123", "encodedPassword")).thenReturn(false);

        assertThrows(UnauthorizedException.class, () -> authService.login(validRequest));
    }

    @Test
    public void testLoginWithUserNotFound() {
        when(userRepository.findByEmail("nonexistent@example.com")).thenReturn(Optional.empty());

        LoginRequest request = LoginRequest.builder()
                .email("nonexistent@example.com")
                .password("testpass123")
                .build();

        assertThrows(UnauthorizedException.class, () -> authService.login(request));
    }
}
