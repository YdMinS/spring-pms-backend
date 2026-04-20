package com.pms.service;

import com.pms.domain.Role;
import com.pms.domain.User;
import com.pms.dto.request.RegisterRequest;
import com.pms.dto.request.UpdateRoleRequest;
import com.pms.dto.request.UpdateUserRequest;
import com.pms.dto.response.UserResponse;
import com.pms.exception.DuplicateEmailException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.UserRepository;
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
public class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private BCryptPasswordEncoder bCryptPasswordEncoder;

    @InjectMocks
    private UserServiceImpl userService;

    private User testUser;

    @BeforeEach
    public void setUp() {
        testUser = User.builder()
                .id(1L)
                .email("test@example.com")
                .password("encodedPassword")
                .name("Test User")
                .role(Role.USER)
                .build();
    }

    @Test
    public void testRegisterWithValidRequest() {
        RegisterRequest request = RegisterRequest.builder()
                .email("newuser@example.com")
                .password("testpass123")
                .name("New User")
                .build();

        when(userRepository.existsByEmail("newuser@example.com")).thenReturn(false);
        when(bCryptPasswordEncoder.encode("testpass123")).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenReturn(testUser);

        UserResponse response = userService.register(request);

        assertNotNull(response);
        assertEquals("test@example.com", response.getEmail());
        assertEquals("Test User", response.getName());
        assertEquals(Role.USER, response.getRole());
    }

    @Test
    public void testRegisterWithDuplicateEmail() {
        RegisterRequest request = RegisterRequest.builder()
                .email("test@example.com")
                .password("testpass123")
                .name("New User")
                .build();

        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        assertThrows(DuplicateEmailException.class, () -> userService.register(request));
    }

    @Test
    public void testGetUserWithValidId() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        UserResponse response = userService.getUser(1L);

        assertNotNull(response);
        assertEquals("test@example.com", response.getEmail());
    }

    @Test
    public void testGetUserWithInvalidId() {
        when(userRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.getUser(9999L));
    }

    @Test
    public void testUpdateUserWithValidRequest() {
        UpdateUserRequest request = UpdateUserRequest.builder()
                .name("Updated Name")
                .build();

        User updatedUser = testUser.toBuilder().name("Updated Name").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenReturn(updatedUser);

        UserResponse response = userService.updateUser(1L, request);

        assertNotNull(response);
        assertEquals("Updated Name", response.getName());
    }

    @Test
    public void testUpdateUserWithInvalidId() {
        UpdateUserRequest request = UpdateUserRequest.builder()
                .name("Updated Name")
                .build();

        when(userRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.updateUser(9999L, request));
    }

    @Test
    public void testDeleteUserWithValidId() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        assertDoesNotThrow(() -> userService.deleteUser(1L));
        verify(userRepository, times(1)).delete(testUser);
    }

    @Test
    public void testDeleteUserWithInvalidId() {
        when(userRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.deleteUser(9999L));
    }

    @Test
    public void testUpdateRoleWithValidRequest() {
        UpdateRoleRequest request = UpdateRoleRequest.builder()
                .role(Role.ADMIN)
                .build();

        User updatedUser = testUser.toBuilder().role(Role.ADMIN).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenReturn(updatedUser);

        UserResponse response = userService.updateRole(1L, request);

        assertNotNull(response);
        assertEquals(Role.ADMIN, response.getRole());
    }

    @Test
    public void testUpdateRoleWithInvalidId() {
        UpdateRoleRequest request = UpdateRoleRequest.builder()
                .role(Role.ADMIN)
                .build();

        when(userRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.updateRole(9999L, request));
    }
}
