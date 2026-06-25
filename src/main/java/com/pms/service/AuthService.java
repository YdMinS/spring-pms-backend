package com.pms.service;

import com.pms.dto.request.LoginRequest;
import com.pms.dto.request.RefreshTokenRequest;
import com.pms.dto.response.AuthResponse;

public interface AuthService {
    AuthResponse login(LoginRequest request);

    AuthResponse refresh(RefreshTokenRequest request);

    AuthResponse getCurrentUser();

    void logout(String refreshToken);
}
