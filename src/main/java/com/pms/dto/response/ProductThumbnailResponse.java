package com.pms.dto.response;

import com.pms.domain.ProductThumbnail;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Per-product-per-seller thumbnail response")
public class ProductThumbnailResponse {

    @Schema(description = "Thumbnail id", example = "1")
    private Long id;

    @Schema(description = "Product id", example = "10")
    private Long productId;

    @Schema(description = "Seller id", example = "3")
    private Long sellerId;

    @Schema(description = "Seller name (resolved)", example = "행복상회")
    private String sellerName;

    @Schema(description = "Template used (null for MANUAL_OVERRIDE)", example = "5", nullable = true)
    private Long templateId;

    @Schema(description = "Result public URL / disk path", example = "https://bucket.s3.../thumb_10_3_1723.jpg")
    private String imageUrl;

    @Schema(description = "GENERATED or MANUAL_OVERRIDE", example = "GENERATED")
    private ProductThumbnail.Source source;

    @Schema(description = "Render/override execution time")
    private LocalDateTime generatedAt;
}
