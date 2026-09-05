package com.pms.service.claim;

import com.pms.domain.ClaimStatus;

import java.time.LocalDateTime;

/**
 * 플랫폼 응답에서 뽑아낸 클레임 1라인 (FEATURE_2609_18).
 *
 * 파서(순수)와 upsert 사이의 계약이다 — 엔티티가 아니므로 계정·테넌트·claimType·syncedAt 처럼
 * 응답 밖에서 결정되는 값은 담지 않는다({@link ClaimUpserter} 가 채운다).
 *
 * <p>positional record 다 — 필드를 더하면 생성 지점(반품·교환 파서, 테스트 헬퍼)이 함께 깨진다.
 * {@code reship*}·{@code collectStatus} 는 교환 전용이라 반품 파서는 null 을 넘기고,
 * {@code returnShippingCharge} 는 반대로 반품 전용이라 교환 파서가 null 을 넘긴다(컬럼 이름이 곧
 * 의미다 — 교환 배송비가 필요해지면 그때 컬럼을 만든다).
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
        String collectStatus,
        String reasonCode,
        String reasonText,
        String faultType,
        Integer returnShippingCharge,
        String collectInvoiceNo,
        String collectCarrierCode,
        String reshipInvoiceNo,
        String reshipCarrierCode,
        String requesterName,
        LocalDateTime receivedAt,
        LocalDateTime platformModifiedAt) {
}
