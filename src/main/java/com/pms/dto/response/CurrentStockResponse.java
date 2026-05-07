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
@Schema(description = "Current stock response")
public class CurrentStockResponse {

    @Schema(description = "Barcode ID", example = "8801500152723")
    private String barcodeId;

    @Schema(description = "Product name (from products table)", example = "Product Name")
    private String productName;

    @Schema(description = "Current in-stock quantity", example = "100")
    private Integer inStock;
}
