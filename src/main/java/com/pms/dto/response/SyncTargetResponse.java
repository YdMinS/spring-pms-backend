package com.pms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 동기화 대상 채널(계정) 1건 (FEATURE_2609_02 / PLAN D2).
 *
 * ⚠️ 자격증명(vendorId / accessKey / secretKey)은 <b>절대 포함하지 않는다</b>. 동기화 진행 화면은
 *    로그인한 사용자 전원이 보므로, 기존 {@link MarketplaceAccountResponse}(accessKey 노출)를
 *    재사용하지 않고 이 DTO 를 따로 둔다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Sync target channel (credentials never exposed)")
public class SyncTargetResponse {

    private Long accountId;
    private Long sellerId;
    private String sellerName;
    private String platform;
    private String accountAlias;

    private String lastSyncStatus;          // SUCCESS / PARTIAL / FAILED / null(기록 없음)
    private LocalDateTime lastSyncAt;       // 마지막 시도
    private LocalDateTime lastOrderSyncAt;  // ordersheets 마지막 성공
    private LocalDateTime lastCancelSyncAt; // 취소 보정 마지막 성공
    private String lastSyncError;           // 서버가 확정한 사유 문구(클라는 가공 없이 노출)
    // vendorId / accessKey / secretKey: 절대 포함하지 않음
}
