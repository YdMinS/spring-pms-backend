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
@Schema(description = "Rename a thumbnail asset (display name only)")
public class TemplateAssetRenameRequest {

    @NotBlank(message = "name is required")
    @Schema(description = "New display name", example = "무료배송 배지")
    private String name;
}
