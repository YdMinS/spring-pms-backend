package com.pms.dto.request;

import com.pms.domain.DetailBlock;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Create/update payload for a {@link com.pms.domain.DetailTemplate} (FEATURE_2608_06 / 17).
 *
 * <p>Mirror of {@link ThumbnailTemplateRequest} for the flow-layout detail page. PATCH treats null fields
 * as "keep existing" (partial update), so the same DTO serves both create and update. No {@code @Setter}
 * (Builder only).</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Detail template create/update request")
public class DetailTemplateRequest {

    @Schema(description = "Template name", example = "기본 상세 템플릿")
    private String name;

    @Schema(description = "Ordered blocks (text / imageZone / asset / spacer)")
    private List<DetailBlock> blocks;

    @Schema(description = "Active flag; defaults to true on create", example = "true", nullable = true)
    private Boolean active;

    @Schema(description = "Make this the tenant default (demotes the existing default); defaults to false",
            example = "false", nullable = true)
    private Boolean isDefault;

    @Schema(description = "Image-processing preset id to apply to detail images (null = none / keep on PATCH)",
            example = "1", nullable = true)
    private Long imageProcessingPresetId;
}
