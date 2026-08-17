package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Margin preset creation/update request")
public class MarginPolicyRequest {

    @NotNull(message = "sellerId is required")
    @Schema(description = "Seller ID", example = "3")
    private Long sellerId;

    @NotBlank(message = "platform is required")
    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @NotNull(message = "marginRate is required")
    @DecimalMin(value = "0.0", message = "marginRate must be >= 0")
    @DecimalMax(value = "0.9999", message = "marginRate must be <= 0.9999")
    @Schema(description = "Net-profit ratio (0.1500 = 15%)", example = "0.1500")
    private BigDecimal marginRate;
}
