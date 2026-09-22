package com.pms.dto.request;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Update product listing request with options (2609_71/D7: 생성 경로 없음)")
public class CreateProductListingRequest {

    @NotNull(message = "Seller ID cannot be null")
    @Schema(description = "Seller ID (who registers this listing)", example = "1")
    private Long sellerId;

    @NotBlank(message = "Platform cannot be blank")
    @Size(max = 50, message = "Platform must not exceed 50 characters")
    @Schema(description = "Platform identifier", example = "COUPANG")
    private String platform;

    @NotBlank(message = "Platform product ID cannot be blank")
    @Size(max = 255, message = "Platform product ID must not exceed 255 characters")
    @Schema(description = "Platform's product ID (업체상품 ID)", example = "12345678")
    private String platformProductId;

    @NotBlank(message = "Name cannot be blank")
    @Size(max = 255, message = "Name must not exceed 255 characters")
    @Schema(description = "Product listing name", example = "Galaxy S21 Bundle")
    private String name;

    @Schema(description = "Category ID (optional)", example = "1")
    private Long categoryId;

    @Schema(description = "Delivery (CarrierRate) ID (optional)", example = "1")
    private Long deliveryId;

    @Schema(description = "Package ID (optional)", example = "1")
    private Long packageId;

    @Valid
    @NotEmpty(message = "Options cannot be empty")
    @Schema(description = "Product listing options")
    private List<OptionRequest> options;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Option within listing")
    public static class OptionRequest {

        @NotBlank(message = "Option name cannot be blank")
        @Size(max = 255, message = "Option name must not exceed 255 characters")
        @Schema(description = "Option name", example = "Size M")
        private String optionName;

        @NotNull(message = "Selling price cannot be null")
        @DecimalMin(value = "0.01", message = "Selling price must be greater than 0")
        @Schema(description = "Selling price", example = "12999.99")
        private BigDecimal sellingPrice;

        @Size(max = 255, message = "Platform option ID must not exceed 255 characters")
        @Schema(description = "Platform option ID (optional)", example = "opt_12345")
        private String platformOptionId;

        // 🔴 2609_71/D8: 구성품(products) 필드는 없다. 셀 옵션의 구성품은 마스터 옵션
        //    (master_product_option_item)이 소유하며, 이 legacy 경로가 만드는 옵션은 전부 채널 전용이라
        //    애초에 마스터가 없다. 필드를 되살리면 화면 입력이 저장되는 것처럼 보이고 실제로는 버려진다.
    }
}
