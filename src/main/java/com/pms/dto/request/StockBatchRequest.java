package com.pms.dto.request;

import com.pms.domain.StockType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Stock batch request")
public class StockBatchRequest {

    @Schema(description = "Stock type (IN or OUT) - applies to all items")
    @NotNull
    private StockType type;

    @Schema(description = "List of items to process")
    @NotEmpty
    @Valid
    private List<StockBatchItem> items;
}
