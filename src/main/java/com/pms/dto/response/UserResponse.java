package com.pms.dto.response;

import com.pms.domain.Role;
import com.pms.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "User response")
public class UserResponse {

    @Schema(description = "User ID", example = "1")
    private Long id;

    @Schema(description = "User email", example = "user@example.com")
    private String email;

    @Schema(description = "User name", example = "John Doe")
    private String name;

    @Schema(description = "User role", example = "GUEST")
    private Role role;

    @Schema(description = "Created timestamp", example = "2024-01-01T00:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "Last updated timestamp", example = "2024-01-01T00:00:00")
    private LocalDateTime updatedAt;

    public static UserResponse of(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
