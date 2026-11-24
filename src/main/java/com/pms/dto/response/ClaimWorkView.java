package com.pms.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One claim opened on 「반품 재입고」 or 「교환 발송」 (FEATURE_2611_34 D19 · D20 · D35 · D42).
 *
 * <p>{@code failure} = {@code UNLINKED} (no order line) or the expansion failure name; then {@code items} is empty and
 * nothing can be put on it (R7). {@code doneQty} is always positive — the service flips the ledger sign.
 */
public record ClaimWorkView(
        Long orderClaimId,
        String claimType,
        String externalOrderId,
        String platform,
        String accountAlias,
        String sellerName,
        String requesterName,
        String claimStatus,
        String collectStatus,
        String itemName,
        int claimQty,
        LocalDateTime receivedAt,
        String failure,
        List<Item> items
) {
    /** One product the claim expands to: required = BOM quantity × claim quantity (R6). */
    public record Item(
            Long productId,
            String productName,
            String imageUrl,
            String barcode,
            int requiredQty,
            int doneQty,
            int remainingQty
    ) {}

    /** Not finished yet (R8): cannot be expanded, or some product still has a remaining count. */
    @JsonIgnore
    public boolean pending() {
        return failure != null || items.stream().anyMatch(item -> item.remainingQty() > 0);
    }
}
