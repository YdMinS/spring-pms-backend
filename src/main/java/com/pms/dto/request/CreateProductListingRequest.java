package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Create product listing request")
public class CreateProductListingRequest {

    @NotBlank(message = "Platform cannot be blank")
    @Size(max = 50, message = "Platform must not exceed 50 characters")
    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @NotBlank(message = "Platform product ID cannot be blank")
    @Size(max = 255, message = "Platform product ID must not exceed 255 characters")
    @Schema(description = "Platform's product ID (업체상품 ID)", example = "12345678")
    private String platformProductId;

    @Schema(description = "Category ID (optional)", example = "1")
    private Long categoryId;

    @Schema(description = "Delivery (CarrierRate) ID (optional)", example = "1")
    private Long deliveryId;

    @Schema(description = "Package ID (optional)", example = "1")
    private Long packageId;
}
