package com.pms.dto.response;

import com.pms.domain.ClaimStatus;

import java.time.LocalDateTime;

/**
 * A return claim whose goods have not (fully) been checked back in (FEATURE_2609_28 / prompt 04 Step 6-2).
 *
 * <p>RETURN_IN requires an {@code orderClaimId}, so this is the list the screen picks from.
 *
 * <p>🔴 The list is NOT filtered by {@code collectStatus}. The claim status mapping from 2609_18/21
 * has never been verified against a live account, and a wrong whitelist would block checking in goods
 * that physically came back. The statuses are shown; the human decides.
 *
 * <p>⚠️ {@code orderLineId} is null when the claim could not be matched to an order — such claims stay
 * in the list and are identified by {@code itemName}.
 */
public record ReturnCandidateView(
        Long orderClaimId,
        Long orderLineId,
        String itemName,
        String externalOrderId,
        int claimQty,
        int receivedQty,
        int remainingQty,
        String claimStatus,
        String collectStatus,
        LocalDateTime receivedAt
) {

    /** Query constructor — derives {@code remainingQty} and flattens the status enum to its name. */
    public ReturnCandidateView(Long orderClaimId, Long orderLineId, String itemName, String externalOrderId,
                               Integer claimQty, Long receivedQty, ClaimStatus claimStatus,
                               String collectStatus, LocalDateTime receivedAt) {
        this(orderClaimId, orderLineId, itemName, externalOrderId,
                claimQty, receivedQty.intValue(), claimQty - receivedQty.intValue(),
                claimStatus == null ? null : claimStatus.name(), collectStatus, receivedAt);
    }
}
