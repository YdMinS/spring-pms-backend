package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Carrier 생성/수정 공용 요청 DTO.
 *
 * PATCH 는 full-replace 시맨틱이므로 name/isActive 둘 다 필수(부분 수정 미지원).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Carrier creation/update request")
public class CarrierRequest {

    @NotBlank(message = "Carrier name is required")
    @Size(max = 100)
    @Schema(description = "Carrier company name", example = "롯데택배")
    private String name;

    @NotNull(message = "isActive is required")
    @Schema(description = "Whether the carrier is active", example = "true")
    private Boolean isActive;
}
