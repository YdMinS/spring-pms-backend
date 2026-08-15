package com.pms.dto.request;

import com.pms.domain.BackgroundMode;
import com.pms.domain.TemplateElement;
import com.pms.domain.TemplateField;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Create/update payload for a {@link com.pms.domain.ThumbnailTemplate}.
 *
 * <p>Validation constraints fire on POST (annotated {@code @Valid}). PATCH is applied WITHOUT
 * {@code @Valid} and treats null fields as "keep existing" (partial update), so the same DTO serves
 * both. Also reused as the inline template inside {@link ThumbnailPreviewRequest}.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Thumbnail template create/update request")
public class ThumbnailTemplateRequest {

    @NotBlank(message = "name is required")
    @Schema(description = "Template name", example = "쿠팡 기본 썸네일")
    private String name;

    @NotNull(message = "canvasWidth is required")
    @Positive(message = "canvasWidth must be > 0")
    @Schema(description = "Canvas width in px", example = "1000")
    private Integer canvasWidth;

    @NotNull(message = "canvasHeight is required")
    @Positive(message = "canvasHeight must be > 0")
    @Schema(description = "Canvas height in px", example = "1000")
    private Integer canvasHeight;

    @Schema(description = "Background paint mode; defaults to WHITE on create when null",
            example = "WHITE", nullable = true)
    private BackgroundMode backgroundMode;

    @Schema(description = "Top gradient color #RRGGBB (required when backgroundMode=GRADIENT_MANUAL)",
            example = "#FF0000", nullable = true)
    private String gradientTopColor;

    @Schema(description = "Bottom gradient color #RRGGBB (required when backgroundMode=GRADIENT_MANUAL)",
            example = "#0000FF", nullable = true)
    private String gradientBottomColor;

    @Schema(description = "Ordered element array (painter's order)")
    private List<TemplateElement> elements;

    @Schema(description = "User-defined input fields (bind targets); custom fields require a defaultValue")
    private List<TemplateField> fields;

    @Schema(description = "Active flag; defaults to true on create", example = "true", nullable = true)
    private Boolean active;

    @Schema(description = "Make this the tenant default (demotes the existing default); defaults to false",
            example = "false", nullable = true)
    private Boolean isDefault;
}
