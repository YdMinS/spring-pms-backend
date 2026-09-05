package com.pms.service.claim;

import com.pms.domain.ClaimStatus;

import java.time.LocalDateTime;

/**
 * 플랫폼 응답에서 뽑아낸 클레임 1라인 (FEATURE_2609_18).
 *
 * 파서(순수)와 upsert 사이의 계약이다 — 엔티티가 아니므로 계정·테넌트·claimType·syncedAt 처럼
 * 응답 밖에서 결정되는 값은 담지 않는다({@link ClaimUpserter} 가 채운다).
 */
public record ClaimRecord(
        String externalClaimId,
        String externalOrderId,
        String externalBoxId,
        String externalItemId,
        String itemName,
        int quantity,
        ClaimStatus status,
        String platformStatus,
        String reasonCode,
        String reasonText,
        String faultType,
        Integer returnShippingCharge,
        String collectInvoiceNo,
        String collectCarrierCode,
        String requesterName,
        LocalDateTime receivedAt,
        LocalDateTime platformModifiedAt) {
}
