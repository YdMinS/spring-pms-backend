package com.pms.dto.request;

import com.pms.domain.TemplateElement;
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

    @Schema(description = "Seller id; null = tenant-wide template", example = "3", nullable = true)
    private Long sellerId;

    @Schema(description = "Full-canvas base image storage key (optional)", nullable = true)
    private String backgroundImageKey;

    @Schema(description = "Ordered element array (painter's order)")
    private List<TemplateElement> elements;

    @Schema(description = "Active flag; defaults to true on create", example = "true", nullable = true)
    private Boolean active;
}
