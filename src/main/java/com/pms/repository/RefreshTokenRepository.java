package com.pms.repository;

import com.pms.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);   // refresh validation

    void deleteByToken(String token);                   // logout
}
