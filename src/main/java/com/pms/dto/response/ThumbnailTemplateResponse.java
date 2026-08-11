package com.pms.dto.response;

import com.pms.domain.TemplateElement;
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

    @Schema(description = "Seller id; null = tenant-wide", example = "3", nullable = true)
    private Long sellerId;

    @Schema(description = "Template name", example = "쿠팡 기본 썸네일")
    private String name;

    @Schema(description = "Canvas width in px", example = "1000")
    private Integer canvasWidth;

    @Schema(description = "Canvas height in px", example = "1000")
    private Integer canvasHeight;

    @Schema(description = "Full-canvas base image storage key", nullable = true)
    private String backgroundImageKey;

    @Schema(description = "Ordered element array")
    private List<TemplateElement> elements;

    @Schema(description = "Active flag", example = "true")
    private Boolean active;
}
