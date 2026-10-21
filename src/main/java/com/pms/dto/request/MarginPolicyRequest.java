package com.pms.dto.request;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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

    /**
     * FEATURE_2610_26 / D58 · D59: the Auction target margin. Required for an ESM preset (checked in the service);
     * ignored and stored as null for every other platform.
     */
    @DecimalMin(value = "0.0", message = "auctionMarginRate must be >= 0")
    @DecimalMax(value = "0.9999", message = "auctionMarginRate must be <= 0.9999")
    @Schema(description = "Auction net-profit ratio (ESM only, required there; 0.1500 = 15%)", example = "0.1500")
    private BigDecimal auctionMarginRate;

    // Optional display discount rate for originalPrice reverse-calc (73). Upper-bounded at 0.5; null = keep
    // existing on update / no discount on create.
    @DecimalMin(value = "0.0", message = "displayDiscountRate must be >= 0")
    @DecimalMax(value = "0.5", message = "displayDiscountRate must be <= 0.5")
    @Schema(description = "Display discount rate (0.2000 = 20% strike-through)", example = "0.2000")
    private BigDecimal displayDiscountRate;

    /**
     * Minimum margin amount in won (FEATURE_2609_39 / D4). Optional on purpose — null means "do not alert on
     * the amount axis", so {@code @NotNull} must never be added here.
     */
    @DecimalMin(value = "0", message = "minMarginAmount must be >= 0")
    @Digits(integer = 8, fraction = 2, message = "minMarginAmount must fit DECIMAL(10,2)")
    @Schema(description = "Minimum margin amount in won; null = condition unused", example = "1000.00")
    private BigDecimal minMarginAmount;

    /** Minimum margin ratio (0.1090 = 10.9%); null = condition unused (FEATURE_2609_39 / D4). */
    @DecimalMin(value = "0.0", message = "minMarginRate must be >= 0")
    @DecimalMax(value = "0.9999", message = "minMarginRate must be <= 0.9999")
    @Digits(integer = 1, fraction = 4, message = "minMarginRate must fit DECIMAL(5,4)")
    @Schema(description = "Minimum margin ratio (0.1090 = 10.9%); null = condition unused", example = "0.1090")
    private BigDecimal minMarginRate;
}
