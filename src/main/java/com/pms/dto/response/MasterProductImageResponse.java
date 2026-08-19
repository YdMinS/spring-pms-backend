package com.pms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
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
@Schema(description = "A master product pool image with its field mapping state (FEATURE_2608_06 / 37)")
public class MasterProductImageResponse {

    @Schema(description = "Image ID", example = "1")
    private Long id;

    @Schema(description = "Position within the pool (0-based)", example = "0")
    private Integer sortOrder;

    @Schema(description = "Stored image URL/path")
    private String imageUrl;

    @Schema(description = "Detail zones this image is mapped to (excludes the cover-photo key)",
            example = "[\"product_photos\", \"detail_photos\"]")
    private List<String> assignedZones;

    @JsonProperty("isSource") // primitive boolean getter isSource() would otherwise serialize as "source"
    @Schema(description = "True if this image is the master's cover photo", example = "false")
    private boolean isSource;
}
