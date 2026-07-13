package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PlatformCarrierCode 응답 DTO.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Platform carrier code response")
public class PlatformCarrierCodeResponse {

    @Schema(description = "Unique identifier for the platform carrier code", example = "1")
    private Long id;

    @Schema(description = "Owning carrier id", example = "1")
    private Long carrierId;

    @Schema(description = "Platform name", example = "COUPANG")
    private String platform;

    @Schema(description = "Delivery company code for this platform", example = "CJGLS")
    private String deliveryCompanyCode;
}
