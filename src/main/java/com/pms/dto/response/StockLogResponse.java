package com.pms.dto.response;

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
@Schema(description = "Stock log response")
public class StockLogResponse {

    @Schema(description = "Stock log ID", example = "1")
    private Long stockId;

    @Schema(description = "Barcode ID", example = "8801500152723")
    private String barcodeId;

    @Schema(description = "Product name", example = "Test Product")
    private String productName;

    @Schema(description = "Current in-stock quantity after transaction", example = "100")
    private Integer inStock;

    @Schema(description = "Quantity added (0 if OUT)", example = "100")
    private Integer stockAdd;

    @Schema(description = "Quantity subtracted (0 if IN)", example = "0")
    private Integer stockSub;

    @Schema(description = "Created timestamp", example = "2026-05-06T10:30:00")
    private LocalDateTime createdDate;
}
