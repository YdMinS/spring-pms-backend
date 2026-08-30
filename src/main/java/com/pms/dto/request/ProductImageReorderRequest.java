package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Reorder a product's gallery to exactly this ordered list of image ids (FEATURE_2608_06 / 39).
 * The set must match the gallery's current image ids exactly, else 400.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Product gallery image IDs in the desired order (must equal the current gallery set)")
public class ProductImageReorderRequest {

    @NotNull
    @Schema(description = "Gallery image IDs in order (exact set match with the product's gallery)")
    private List<Long> imageIds;
}
