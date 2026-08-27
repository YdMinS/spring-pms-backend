package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Schema(description = "Master product response (Design 2)")
public class MasterProductResponse {

    @Schema(description = "Master product ID", example = "1")
    private Long id;

    @Schema(description = "Master product name (internal label)", example = "Galaxy S21 Bundle")
    private String name;

    @Schema(description = "Computed registration name (sellerProductName), single-fetch only; null on the list",
            nullable = true, example = "노브랜드 생수 x 6")
    private String registrationName;

    @Schema(description = "Activation flag (false = soft deleted)", example = "true")
    private Boolean active;

    @Schema(description = "Base image override URL", nullable = true)
    private String sourceImageUrl;

    @Schema(description = "UI input field values (key -> value)")
    private Map<String, String> fieldValues;

    @Schema(description = "Master tag pool (33; deduped, folded into channel cells at push time)")
    private List<String> tags;

    @Schema(description = "Default delivery (CarrierRate) ID for the price engine", nullable = true)
    private Long defaultDeliveryId;

    @Schema(description = "Default box (Package) ID for the price engine", nullable = true)
    private Long defaultPackageId;

    @Schema(description = "옵션확인 suffix master-level override (69); null = inherit", nullable = true)
    private Boolean optionCheckSuffixEnabled;

    @Schema(description = "옵션확인 suffix custom text master-level override (69); null = inherit", nullable = true)
    private String optionCheckSuffix;

    @Schema(description = "Master-level shipping overrides (75; key -> string). Place keys are channel-level only.")
    private Map<String, String> shippingOverride;

    @Schema(description = "Component products (the master's product set)")
    private List<Component> components;

    @Schema(description = "Options (SKU variants)")
    private List<MasterOptionResponse> options;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "A component product of the master")
    public static class Component {

        @Schema(description = "Product ID", example = "3")
        private Long productId;

        @Schema(description = "Product name", example = "Galaxy S21")
        private String productName;
    }
}
