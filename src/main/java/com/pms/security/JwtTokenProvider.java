package com.pms.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long expirationTime;
    private final long refreshExpirationTime;

    public JwtTokenProvider(@Value("${jwt.secret}") String secret,
                           @Value("${jwt.expiration}") long expiration,
                           @Value("${jwt.refresh-expiration}") long refreshExpiration) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.expirationTime = expiration;
        this.refreshExpirationTime = refreshExpiration;
    }

    public String generateToken(Authentication authentication) {
        String email = authentication.getName();
        String role = authentication.getAuthorities().stream()
                .findFirst()
                .map(auth -> auth.getAuthority().replace("ROLE_", ""))
                .orElse("GUEST");
        Long tenantId = ((CustomUserDetails) authentication.getPrincipal()).getTenantId();
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationTime);

        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .claim("tenantId", tenantId)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(secretKey)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getEmailFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    public Long getTenantIdFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        // JJWT deserializes numeric claims as Integer when small; read via Number to avoid
        // ClassCastException from a direct get("tenantId", Long.class).
        Number tenantId = claims.get("tenantId", Number.class);
        return tenantId == null ? null : tenantId.longValue();
    }

    public long getExpirationTime() {
        return expirationTime;
    }

    // Opaque refresh token value (UUID, not a JWT). Stored DB-side.
    public String generateRefreshTokenValue() {
        return UUID.randomUUID().toString();
    }

    public long getRefreshExpirationTime() {
        return refreshExpirationTime;
    }
}
