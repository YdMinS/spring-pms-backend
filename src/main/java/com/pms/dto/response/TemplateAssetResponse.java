package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Template asset (reusable fixed image for thumbnails)")
public class TemplateAssetResponse {

    @Schema(description = "Asset id", example = "1")
    private Long id;

    @Schema(description = "Display name", example = "무료배송 배지")
    private String name;

    @Schema(description = "Stored value — used both for the canvas <img> and as element.src",
            example = "thumbnail-assets/asset_1712345678901_a1b2c3d4.png")
    private String storageKey;

    @Schema(description = "MIME type", example = "image/png")
    private String contentType;
}
