package com.pms.dto.response;

import com.pms.domain.BackgroundMode;
import com.pms.domain.TemplateElement;
import com.pms.domain.TemplateField;
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
@Schema(description = "Thumbnail template response")
public class ThumbnailTemplateResponse {

    @Schema(description = "Template id", example = "1")
    private Long id;

    @Schema(description = "Template name", example = "쿠팡 기본 썸네일")
    private String name;

    @Schema(description = "Canvas width in px", example = "1000")
    private Integer canvasWidth;

    @Schema(description = "Canvas height in px", example = "1000")
    private Integer canvasHeight;

    @Schema(description = "Background paint mode", example = "WHITE")
    private BackgroundMode backgroundMode;

    @Schema(description = "Top gradient color #RRGGBB (GRADIENT_MANUAL only)", nullable = true)
    private String gradientTopColor;

    @Schema(description = "Bottom gradient color #RRGGBB (GRADIENT_MANUAL only)", nullable = true)
    private String gradientBottomColor;

    @Schema(description = "Ordered element array")
    private List<TemplateElement> elements;

    @Schema(description = "User-defined input fields (bind targets)")
    private List<TemplateField> fields;

    @Schema(description = "Active flag", example = "true")
    private Boolean active;

    @Schema(description = "Whether this is the tenant default template", example = "true")
    private Boolean isDefault;
}
