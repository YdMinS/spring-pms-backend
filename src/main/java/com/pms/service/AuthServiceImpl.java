package com.pms.service;

import com.pms.domain.RefreshToken;
import com.pms.domain.User;
import com.pms.dto.request.LoginRequest;
import com.pms.dto.request.RefreshTokenRequest;
import com.pms.dto.response.AuthResponse;
import com.pms.exception.UnauthorizedException;
import com.pms.repository.RefreshTokenRepository;
import com.pms.repository.UserRepository;
import com.pms.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        Authentication authentication = buildAuthentication(user);
        String token = jwtTokenProvider.generateToken(authentication);

        RefreshToken rt = RefreshToken.builder()
                .token(jwtTokenProvider.generateRefreshTokenValue())
                .user(user)
                .deviceInfo(null)   // display-only metadata, unused for now
                .expiresAt(LocalDateTime.now().plus(
                        jwtTokenProvider.getRefreshExpirationTime(), ChronoUnit.MILLIS))
                .build();
        refreshTokenRepository.save(rt);

        return buildAuthResponse(user, token, rt.getToken());
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshToken rt = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        if (rt.isExpired()) {
            throw new UnauthorizedException("Refresh token expired");
        }

        User user = rt.getUser();
        String token = jwtTokenProvider.generateToken(buildAuthentication(user));

        // No rotation: return the same refresh token value.
        return buildAuthResponse(user, token, rt.getToken());
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new UnauthorizedException("Not authenticated");
        }

        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException("User not found"));

        String token = jwtTokenProvider.generateToken(authentication);

        // /me issues a fresh access token; refreshToken stays null.
        return buildAuthResponse(user, token, null);
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenRepository.deleteByToken(refreshToken);
    }

    private Authentication buildAuthentication(User user) {
        return new UsernamePasswordAuthenticationToken(
                user.getEmail(),
                null,
                java.util.Collections.singletonList(
                        new SimpleGrantedAuthority("ROLE_" + user.getRole().getKey())
                )
        );
    }

    private AuthResponse buildAuthResponse(User user, String token, String refreshToken) {
        long expiresIn = System.currentTimeMillis() + jwtTokenProvider.getExpirationTime();
        return AuthResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole())
                .expiresIn(expiresIn)
                .build();
    }
}
