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
@Schema(description = "A master product input image (zone member)")
public class MasterProductImageResponse {

    @Schema(description = "Image ID", example = "1")
    private Long id;

    @Schema(description = "Zone the image belongs to", example = "product_photos")
    private String zoneId;

    @Schema(description = "Position within the zone (0-based)", example = "0")
    private Integer sortOrder;

    @Schema(description = "Stored image URL/path")
    private String imageUrl;
}
