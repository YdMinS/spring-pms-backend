package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Reorder the images of one zone (imageIds in the desired order)")
public class MasterImageReorderRequest {

    @NotBlank
    @Schema(description = "Zone whose images are reordered", example = "product_photos")
    private String zoneId;

    @NotEmpty
    @Schema(description = "Image IDs in the desired order (must exactly match the zone's image set)")
    private List<Long> imageIds;
}
