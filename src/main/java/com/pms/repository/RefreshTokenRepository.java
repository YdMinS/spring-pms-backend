package com.pms.repository;

import com.pms.domain.RefreshToken;
import com.pms.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);   // refresh validation

    void deleteByToken(String token);                   // logout

    // Bulk-delete a user's tokens before deleting the user (refresh_token.user_id is NOT NULL).
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.user = :user")
    void deleteByUser(@Param("user") User user);
}
