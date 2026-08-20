package com.pms.dto.response;

import com.pms.domain.ProductImage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One image in a product's gallery (FEATURE_2608_06 / 39). {@code imageUrl} is the stored value as-is
 * (disk-relative path on local/test, public URL on dev/prod — same convention as
 * {@code ProductController.getImage}; no double-wrapping).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A product gallery image (FEATURE_2608_06 / 39)")
public class ProductImageResponse {

    @Schema(description = "Image ID (stable slot the master pool references)", example = "1")
    private Long id;

    @Schema(description = "Owning product ID", example = "10")
    private Long productId;

    @Schema(description = "Position within the gallery (0-based)", example = "0")
    private int sortOrder;

    @Schema(description = "Stored image URL/path")
    private String imageUrl;

    public static ProductImageResponse from(ProductImage image) {
        return ProductImageResponse.builder()
                .id(image.getId())
                .productId(image.getProduct().getId())
                .sortOrder(image.getSortOrder())
                .imageUrl(image.getImageUrl())
                .build();
    }
}
