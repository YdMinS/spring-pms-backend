package com.pms.dto.request;

import com.pms.domain.Role;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateRoleRequest {

    @NotNull(message = "Role is required")
    private Role role;
}
