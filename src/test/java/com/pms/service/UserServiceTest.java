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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    public void testUpdateUserNameWithValidRequest() {
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
    public void testUpdateUserEmailWithValidRequest() {
        UpdateUserRequest request = UpdateUserRequest.builder()
                .email("newemail@example.com")
                .build();

        User updatedUser = testUser.toBuilder().email("newemail@example.com").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(userRepository.existsByEmail("newemail@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(updatedUser);

        UserResponse response = userService.updateUser(1L, request);

        assertNotNull(response);
        assertEquals("newemail@example.com", response.getEmail());
    }

    @Test
    public void testUpdateUserEmailWithDuplicateEmail() {
        UpdateUserRequest request = UpdateUserRequest.builder()
                .email("other@example.com")
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(userRepository.existsByEmail("other@example.com")).thenReturn(true);

        assertThrows(DuplicateEmailException.class, () -> userService.updateUser(1L, request));
    }

    @Test
    public void testUpdateUserRoleWithValidRequest() {
        UpdateUserRequest request = UpdateUserRequest.builder()
                .role(Role.ADMIN)
                .build();

        User updatedUser = testUser.toBuilder().role(Role.ADMIN).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenReturn(updatedUser);

        UserResponse response = userService.updateUser(1L, request);

        assertNotNull(response);
        assertEquals(Role.ADMIN, response.getRole());
    }

    @Test
    public void testUpdateUserPasswordWithValidRequest() {
        UpdateUserRequest request = UpdateUserRequest.builder()
                .password("newPassword123")
                .build();

        User updatedUser = testUser.toBuilder().password("encodedNewPassword").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(bCryptPasswordEncoder.encode("newPassword123")).thenReturn("encodedNewPassword");
        when(userRepository.save(any(User.class))).thenReturn(updatedUser);

        UserResponse response = userService.updateUser(1L, request);

        assertNotNull(response);
        verify(bCryptPasswordEncoder, times(1)).encode("newPassword123");
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    public void testUpdateUserMultipleFields() {
        UpdateUserRequest request = UpdateUserRequest.builder()
                .name("New Name")
                .email("newemail@example.com")
                .password("newPassword123")
                .role(Role.ADMIN)
                .build();

        User updatedUser = testUser.toBuilder()
                .name("New Name")
                .email("newemail@example.com")
                .password("encodedNewPassword")
                .role(Role.ADMIN)
                .build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(userRepository.existsByEmail("newemail@example.com")).thenReturn(false);
        when(bCryptPasswordEncoder.encode("newPassword123")).thenReturn("encodedNewPassword");
        when(userRepository.save(any(User.class))).thenReturn(updatedUser);

        UserResponse response = userService.updateUser(1L, request);

        assertNotNull(response);
        assertEquals("New Name", response.getName());
        assertEquals("newemail@example.com", response.getEmail());
        assertEquals(Role.ADMIN, response.getRole());
        verify(bCryptPasswordEncoder, times(1)).encode("newPassword123");
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

    @Test
    public void testCheckEmailExists() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        boolean exists = userService.checkEmailExists("test@example.com");

        assertTrue(exists);
        verify(userRepository, times(1)).existsByEmail("test@example.com");
    }

    @Test
    public void testCheckEmailNotExists() {
        when(userRepository.existsByEmail("notfound@example.com")).thenReturn(false);

        boolean exists = userService.checkEmailExists("notfound@example.com");

        assertFalse(exists);
    }

    @Test
    public void testListUsersWithoutSearch() {
        User user2 = User.builder()
                .id(2L)
                .email("user2@example.com")
                .password("encodedPassword")
                .name("User Two")
                .role(Role.USER)
                .build();

        Page<User> userPage = new PageImpl<>(Arrays.asList(testUser, user2), PageRequest.of(0, 20), 2);
        when(userRepository.findAll(any(Pageable.class))).thenReturn(userPage);

        Page<UserResponse> response = userService.listUsers(0, 20, null);

        assertNotNull(response);
        assertEquals(2, response.getTotalElements());
        assertEquals(2, response.getContent().size());
        verify(userRepository, times(1)).findAll(any(Pageable.class));
    }

    @Test
    public void testListUsersWithSearch() {
        Page<User> userPage = new PageImpl<>(Arrays.asList(testUser), PageRequest.of(0, 20), 1);
        when(userRepository.searchByKeyword(eq("Test"), any(Pageable.class))).thenReturn(userPage);

        Page<UserResponse> response = userService.listUsers(0, 20, "Test");

        assertNotNull(response);
        assertEquals(1, response.getTotalElements());
        assertEquals("Test User", response.getContent().get(0).getName());
        verify(userRepository, times(1)).searchByKeyword(eq("Test"), any(Pageable.class));
    }

    @Test
    public void testListUsersWithInvalidPageSize() {
        Page<User> userPage = new PageImpl<>(Arrays.asList(testUser), PageRequest.of(0, 20), 1);
        when(userRepository.findAll(any(Pageable.class))).thenReturn(userPage);

        Page<UserResponse> response = userService.listUsers(0, -1, null);

        assertNotNull(response);
        verify(userRepository, times(1)).findAll(any(Pageable.class));
    }
}
