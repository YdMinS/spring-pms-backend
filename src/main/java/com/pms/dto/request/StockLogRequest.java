package com.pms.dto.request;

import com.pms.domain.StockType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Stock log request")
public class StockLogRequest {

    @Schema(description = "Barcode ID", example = "8801500152723")
    @NotBlank
    private String barcodeId;

    @Schema(description = "Stock type (IN or OUT)")
    @NotNull
    private StockType type;

    @Schema(description = "Quantity", example = "100")
    @NotNull
    @Min(1)
    private Integer quantity;

    @Schema(description = "Product name", example = "Test Product")
    @NotBlank
    private String name;
}
