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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private static final int DEFAULT_PAGE_SIZE = 20;

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateEmailException(request.getEmail());
        }

        String encodedPassword = passwordEncoder.encode(request.getPassword());

        User user = User.builder()
                .email(request.getEmail())
                .password(encodedPassword)
                .name(request.getName())
                .role(Role.GUEST)
                .build();

        User savedUser = userRepository.save(user);
        return UserResponse.of(savedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        return UserResponse.of(user);
    }

    @Override
    @Transactional
    public UserResponse updateUser(Long id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));

        user = user.toBuilder()
                .name(request.getName())
                .build();

        User updatedUser = userRepository.save(user);
        return UserResponse.of(updatedUser);
    }

    @Override
    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        userRepository.delete(user);
    }

    @Override
    @Transactional
    public UserResponse updateRole(Long id, UpdateRoleRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));

        user = user.toBuilder()
                .role(request.getRole())
                .build();

        User updatedUser = userRepository.save(user);
        return UserResponse.of(updatedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean checkEmailExists(String email) {
        return userRepository.existsByEmail(email);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserResponse> listUsers(int page, int size, String search) {
        if (size <= 0) {
            size = DEFAULT_PAGE_SIZE;
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<User> userPage;
        if (search == null || search.trim().isEmpty()) {
            userPage = userRepository.findAll(pageable);
        } else {
            userPage = userRepository.searchByKeyword(search.trim(), pageable);
        }

        return userPage.map(UserResponse::of);
    }
}
