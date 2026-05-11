package com.pms.service;

import com.pms.dto.request.RegisterRequest;
import com.pms.dto.request.UpdateRoleRequest;
import com.pms.dto.request.UpdateUserRequest;
import com.pms.dto.response.UserResponse;
import org.springframework.data.domain.Page;

public interface UserService {
    UserResponse register(RegisterRequest request);

    UserResponse getUser(Long id);

    UserResponse updateUser(Long id, UpdateUserRequest request);

    void deleteUser(Long id);

    UserResponse updateRole(Long id, UpdateRoleRequest request);

    boolean checkEmailExists(String email);

    Page<UserResponse> listUsers(int page, int size, String search);
}
