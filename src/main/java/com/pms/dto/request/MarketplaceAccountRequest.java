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

    // FEATURE_2610_13: required for COUPANG only — checked in the service (an 11st account has no vendor ID).
    @Schema(description = "Coupang vendor ID (COUPANG only, required there)", example = "A00012345")
    private String vendorId;

    @Schema(description = "WING login ID (optional; distinct from vendorId)", example = "wing_user01")
    private String vendorUserId;

    @Schema(description = "Coupang access key (COUPANG only, required there)")
    private String accessKey;

    @Schema(description = "Coupang secret key (write-only, stored encrypted). Required on create; blank on update keeps existing.")
    private String secretKey;

    @Schema(description = "11st Open API key (ELEVENST only, write-only, stored encrypted). Required on create; "
            + "blank on update keeps existing.")
    private String apiKey;

    @Schema(description = "Active flag (null defaults to true on create)", example = "true")
    private Boolean isActive;

    @Schema(description = "Thumbnail template override for this seller×platform (null = tenant default). "
            + "On update, null keeps the existing assignment.", example = "3")
    private Long thumbnailTemplateId;

    @Schema(description = "Detail template override for this seller×platform (null = tenant default). "
            + "On update, null keeps the existing assignment.", example = "5")
    private Long detailTemplateId;
}
