package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Carrier rate creation/update request")
public class CarrierRateRequest {

    @NotNull(message = "carrierId is required")
    @Schema(description = "Carrier master ID", example = "4")
    private Long carrierId;

    @NotBlank(message = "Type is required")
    @Schema(description = "Delivery type", example = "EXPRESS")
    private String type;

    @NotNull(message = "Cost is required")
    @DecimalMin(value = "0", inclusive = false, message = "Cost must be > 0")
    @Schema(description = "Delivery cost in currency", example = "15.50")
    private BigDecimal cost;

    // Optional: on create the server fills today when omitted; on update the existing value is kept.
    @Schema(description = "가격이 적용되기 시작하는 날. 비우면 생성 시 오늘로 채워진다", example = "2026-05-13")
    private LocalDate effectiveDate;

    @NotNull(message = "isDefault is required")
    @Schema(description = "Whether this is the default carrier rate", example = "false")
    private Boolean isDefault;
}
