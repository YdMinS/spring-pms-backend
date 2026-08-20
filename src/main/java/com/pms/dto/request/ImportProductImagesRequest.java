package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Import product image slots into the master's pool as <b>reference</b> entries (FEATURE_2608_06 / 40).
 * Each id is a {@code ProductImage} slot that will be live-linked (no copy).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Product image slot IDs to live-link into the master's pool as reference entries")
public class ImportProductImagesRequest {

    @NotEmpty
    @Schema(description = "ProductImage slot IDs to reference (must belong to a tenant-owned product)")
    private List<Long> productImageIds;
}
