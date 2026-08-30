package com.pms.dto.response;

import com.pms.domain.DetailBlock;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Detail-page template (block array)")
public class DetailTemplateResponse {

    @Schema(description = "Template ID", example = "1")
    private Long id;

    @Schema(description = "Template name", example = "기본 상세 템플릿")
    private String name;

    @Schema(description = "Ordered blocks (text / imageZone / asset)")
    private List<DetailBlock> blocks;

    @Schema(description = "Active flag", example = "true")
    private Boolean active;

    @Schema(description = "Whether this is the tenant default", example = "true")
    private Boolean isDefault;

    @Schema(description = "Applied image-processing preset id (null = none)", example = "1", nullable = true)
    private Long imageProcessingPresetId;
}
