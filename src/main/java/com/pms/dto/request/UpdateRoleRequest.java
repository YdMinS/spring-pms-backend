package com.pms.dto.request;

import com.pms.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Role update request")
public class UpdateRoleRequest {

    @NotNull(message = "Role is required")
    @Schema(description = "New role (GUEST/USER/ADMIN)", example = "USER")
    private Role role;
}
