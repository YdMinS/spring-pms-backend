package com.pms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 플랫폼 계정 생성/수정 요청 DTO.
 *
 * - secretKey: 입력 전용. update 시 빈/누락이면 기존값 유지(재암호화 회피).
 * - isActive: null 이면 생성 시 true 기본.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Marketplace account creation/update request")
public class MarketplaceAccountRequest {

    @NotNull(message = "sellerId is required")
    @Schema(description = "Owning seller ID", example = "1", required = true)
    private Long sellerId;

    @NotBlank(message = "platform is required")
    @Schema(description = "Platform name", example = "COUPANG", required = true)
    private String platform;

    @Schema(description = "Account alias", example = "쿠팡 본점")
    private String accountAlias;

    @NotBlank(message = "vendorId is required")
    @Schema(description = "Coupang vendor ID", example = "A00012345", required = true)
    private String vendorId;

    @NotBlank(message = "accessKey is required")
    @Schema(description = "Coupang access key", required = true)
    private String accessKey;

    @Schema(description = "Coupang secret key (write-only, stored encrypted). Required on create; blank on update keeps existing.")
    private String secretKey;

    @Schema(description = "Active flag (null defaults to true on create)", example = "true")
    private Boolean isActive;
}
