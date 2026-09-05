package com.pms.dto.response;

import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.service.claim.ClaimActionOption;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 클레임(반품·교환) 응답 DTO — GET /api/claims, GET /api/claims/{id} (FEATURE_2609_18).
 *
 * {@code platform} 은 처리 액션이 붙기 전인 지금부터 내려보낸다(D5) — 나중에 추가하면 클라이언트 3개를
 * 다시 건드려야 한다. {@code availableActions} 는 실제 액션을 보고 확정했다(FEATURE_2609_21 D1):
 * <b>서버가 내려주고 UI 는 렌더만 한다</b>. 판정의 진입점은 {@code ClaimActionService} 하나이며
 * ({@code ClaimQueryServiceImpl} 은 어댑터를 직접 들지 않는다), 액션이 없는 상태·미지원 플랫폼·
 * 비-ADMIN 사용자에게는 <b>빈 목록</b>이다(예외 아님).
 *
 * {@code collect*} 는 회수(고객→판매자), {@code reship*} 는 재발송(판매자→고객) 송장이다 —
 * 방향이 다르므로 한 쌍으로 합치지 말 것. 재발송은 교환에만 채워진다.
 *
 * {@code orderItemId} 가 null 이면 주문 라인 미연결(D12) — 화면은 {@code linked} 로 배지를 띄우고
 * {@code itemName} 으로 최소 정보를 보여준다.
 */
public record OrderClaimResponse(
        Long id,
        String platform,
        ClaimType claimType,
        ClaimStatus status,
        String platformStatus,
        String externalClaimId,
        String externalOrderId,
        String itemName,
        Integer quantity,
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
        Long sellerId,
        String sellerName,
        Long orderItemId,
        boolean linked,
        List<ClaimActionOption> availableActions) {
}
