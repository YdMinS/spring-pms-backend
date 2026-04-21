package com.pms.dto.response;

import com.pms.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Authentication response")
public class AuthResponse {

    @Schema(description = "JWT access token", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String token;

    @Schema(description = "Token type", example = "Bearer")
    private String tokenType;

    @Schema(description = "Authenticated user email", example = "user@example.com")
    private String email;

    @Schema(description = "Authenticated user name", example = "John Doe")
    private String name;

    @Schema(description = "Authenticated user role", example = "USER")
    private Role role;

    @Schema(description = "Token expiration timestamp (milliseconds)", example = "1735689600000")
    private Long expiresIn;
}
