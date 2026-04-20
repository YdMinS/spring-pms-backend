package com.pms.service;

import com.pms.dto.request.LoginRequest;
import com.pms.dto.response.AuthResponse;

public interface AuthService {
    AuthResponse login(LoginRequest request);
}
