package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Carrier 응답 DTO.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Carrier response")
public class CarrierResponse {

    @Schema(description = "Unique identifier for the carrier", example = "1")
    private Long id;

    @Schema(description = "Carrier company name", example = "롯데택배")
    private String name;

    @Schema(description = "Whether the carrier is active", example = "true")
    private Boolean isActive;
}
