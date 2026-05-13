package com.pms.dto.request;

import com.pms.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "User update request")
public class UpdateUserRequest {

    @Size(min = 2, max = 50, message = "Name must be 2-50 characters")
    @Schema(description = "New user name (optional, 2-50 characters)", example = "Jane Doe")
    private String name;

    @Email(message = "Email must be valid")
    @Schema(description = "New user email (optional, must be unique)", example = "newemail@example.com")
    private String email;

    @Size(min = 8, max = 20, message = "Password must be 8-20 characters")
    @Schema(description = "New user password (optional, 8-20 characters, ADMIN only)", example = "newPassword123")
    private String password;

    @Schema(description = "New user role (optional, ADMIN only)", example = "USER", allowableValues = {"GUEST", "USER", "ADMIN"})
    private Role role;
}
