package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 플랫폼 계정 응답 DTO.
 *
 * ⚠️ secretKey 필드 없음 — 민감 자격증명은 응답에 절대 노출하지 않는다.
 *    accessKey 는 식별자라 노출 가능.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Marketplace account response (secretKey never exposed)")
public class MarketplaceAccountResponse {

    private Long id;
    private Long sellerId;
    private String platform;
    private String accountAlias;
    private String vendorId;
    private String accessKey;          // accessKey는 식별자라 노출 가능
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // secretKey: 응답에 절대 포함하지 않음
}
