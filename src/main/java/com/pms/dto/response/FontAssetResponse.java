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

    @Schema(description = "CSS font-family fallback stack for detail-page HTML; null = none",
            example = "'Nanum Gothic','Malgun Gothic',sans-serif")
    private String webStack;

    @Schema(description = "Public URL of the font binary (@font-face source); null = stack only. "
            + "⚠️ Never the raw storageKey disk path — see FontAsset.publicWebUrl().",
            example = "https://bucket.s3.ap-northeast-2.amazonaws.com/tenants/_system/fonts/system-sans.ttf")
    private String webUrl;
}
