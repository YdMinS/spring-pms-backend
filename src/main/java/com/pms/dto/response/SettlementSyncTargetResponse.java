package com.pms.dto.response;

import java.time.LocalDateTime;

/**
 * 정산 동기화 대상 채널 1건 (FEATURE_2609_30 / PLAN D11).
 *
 * <p>화면의 "마지막 갱신 N시간 전" 과 [갱신] 버튼 활성 여부가 이 값으로 결정된다.
 *
 * <p>⚠️ 자격증명(vendorId / accessKey / secretKey)은 <b>절대 포함하지 않는다</b>
 * ({@link SyncTargetResponse} 와 같은 이유).
 *
 * @param lastSettlementSyncAt 매출내역 적재가 마지막으로 <b>끝까지 성공</b>한 시각 (null = 이력 없음)
 * @param lastPayoutSyncAt     지급내역(02) 이 마지막으로 끝까지 성공한 시각. 02 머지 전에는 항상 null
 * @param nextAvailableAt      수동 갱신 최소 간격이 풀리는 시각. null = 지금 바로 갱신 가능
 */
public record SettlementSyncTargetResponse(
        Long accountId,
        Long sellerId,
        String sellerName,
        String platform,
        String accountAlias,
        LocalDateTime lastSettlementSyncAt,
        LocalDateTime lastPayoutSyncAt,
        LocalDateTime nextAvailableAt) {
}
