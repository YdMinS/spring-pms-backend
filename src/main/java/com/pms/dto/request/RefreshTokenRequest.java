package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Refresh token request")
public class RefreshTokenRequest {

    @NotBlank(message = "refreshToken is required")
    @Schema(description = "Refresh token", example = "550e8400-e29b-41d4-a716-446655440000")
    private String refreshToken;
}
