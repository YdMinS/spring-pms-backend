package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PlatformCarrierCode 생성/수정 공용 요청 DTO.
 *
 * PATCH 는 full-replace 시맨틱이므로 platform/deliveryCompanyCode 둘 다 필수(부분 수정 미지원).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Platform carrier code creation/update request")
public class PlatformCarrierCodeRequest {

    @NotBlank(message = "platform is required")
    @Size(max = 50)
    @Schema(example = "COUPANG")
    private String platform;

    @NotBlank(message = "deliveryCompanyCode is required")
    @Size(max = 50)
    @Schema(example = "CJGLS")
    private String deliveryCompanyCode;
}
