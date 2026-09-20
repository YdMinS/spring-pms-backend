package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Copy (by reference) other products' gallery images into this product's gallery (FEATURE_2609_62).
 *
 * <p>The ids may belong to any product of the caller's tenant; each source row's {@code imageUrl} is shared
 * with a new row (no file re-upload). Ids that no longer exist or belong to another tenant are skipped
 * silently — only an all-skipped request is a 400.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Source gallery image IDs to copy into this product's gallery (reference copy)")
public class ProductImageCopyRequest {

    @NotEmpty(message = "복제할 이미지가 없습니다")
    @Schema(description = "Source ProductImage IDs, in the order they should be appended")
    private List<Long> sourceImageIds;
}
