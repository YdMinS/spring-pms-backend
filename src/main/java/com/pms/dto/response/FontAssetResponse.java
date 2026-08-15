package com.pms.dto.response;

import com.pms.domain.FontSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Font asset response (editor dropdown item)")
public class FontAssetResponse {

    @Schema(description = "Font id", example = "1")
    private Long id;

    @Schema(description = "UI display name", example = "System Sans")
    private String displayName;

    @Schema(description = "Render family key", example = "SansSerif")
    private String familyKey;

    @Schema(description = "BUNDLED (system) or UPLOADED (tenant)", example = "BUNDLED")
    private FontSource source;

    @Schema(description = "True if system-shared (tenantId null); cannot be deleted by tenants",
            example = "true")
    private Boolean system;
}
