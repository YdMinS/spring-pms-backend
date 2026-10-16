package com.pms.dto.response;

import com.pms.domain.ClaimStatus;
import com.pms.domain.ClaimType;
import com.pms.service.claim.ClaimActionOption;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Claim (return · exchange) response DTO — GET /api/claims, GET /api/claims/{id} (FEATURE_2609_18).
 *
 * {@code platform} is sent from now on, before claim actions exist (D5) — adding it later would touch the three
 * clients again. {@code availableActions} was settled against the real actions (FEATURE_2609_21 D1): <b>the server
 * sends them and the UI only renders</b>. The single entry of the decision is {@code ClaimActionService}
 * ({@code ClaimQueryServiceImpl} holds no adapter itself); a status without actions, an unsupported platform and a
 * non-ADMIN user get an <b>empty list</b> (not an exception).
 *
 * {@code collectStatus} is the exchange pickup status <b>as given</b> (not normalized) — shown on screen, the user
 * can tell <b>why the re-ship button is not there yet</b>. The server makes no Korean label (the value set is not
 * settled, so the {@code faultType} rule applies: label only known values, show the rest as given).
 *
 * {@code collect*} is the pickup invoice (customer → seller), {@code reship*} the re-ship invoice (seller →
 * customer) — opposite directions, do not merge them into one pair. Re-ship is filled for exchanges only.
 *
 * {@code collectInvoiceSource} = {@code PLATFORM} (read from the marketplace by the sync) / {@code LOCAL} (the
 * marketplace rejected it and only we recorded it → the resend target, [쿠팡에 다시 보내기] / [마켓에 다시 보내기],
 * FEATURE_2610_17 / D22) / {@code null} (an older row of unknown source, treated as the platform value).
 * {@code returnDeliveryType} is the pickup type <b>as given</b> and the basis of the screen notice
 * 「고객이 직접 보낸 건이라 넣을 송장이 없습니다」 (FEATURE_2609_70 / D3·D12).
 *
 * A null {@code orderItemId} means the claim is not linked to an order line (D12) — the screen shows the badge from
 * {@code linked} and the minimum info from {@code itemName}.
 */
public record OrderClaimResponse(
        Long id,
        String platform,
        ClaimType claimType,
        ClaimStatus status,
        String platformStatus,
        String collectStatus,
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
        String collectInvoiceSource,
        String returnDeliveryType,
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
