package com.pms.dto.response;

import com.pms.domain.Platform;
import com.pms.domain.PriceChangeReason;
import com.pms.domain.PriceTargetType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of the price change history (FEATURE_2609_28 / PLAN D23).
 *
 * <p>⚠️ The channel block ({@code listingId}, {@code listingName}, {@code platform},
 * {@code masterProductId}) is filled for {@link PriceTargetType#LISTING_SELLING} rows only and is
 * <b>all null</b> on a {@link PriceTargetType#PRODUCT_COST} row — a cost change does not belong to
 * any channel. Blank cells when both kinds are listed together are correct, not missing data.
 *
 * <p>⚠️ {@code masterProductId} is null for a cell that is not linked to a master (an imported /
 * legacy listing). Those rows are simply not reachable by the master filter; the listing filter
 * finds them.
 */
public record PriceChangeView(
        Long id,
        PriceTargetType targetType,
        Long productId,
        String productName,
        Long listingId,
        String listingName,
        Platform platform,
        Long masterProductId,
        Long optionId,
        String optionName,
        BigDecimal oldPrice,
        BigDecimal newPrice,
        BigDecimal diff,
        PriceChangeReason reason,
        Long purchaseRecordId,
        String createdBy,
        LocalDateTime createdAt
) {

    /**
     * Projection constructor — {@code diff} is derived here rather than in the JPQL so the
     * subtraction is not left to the database dialect (and so the query stays readable).
     */
    public PriceChangeView(Long id, PriceTargetType targetType,
                           Long productId, String productName,
                           Long listingId, String listingName, Platform platform, Long masterProductId,
                           Long optionId, String optionName,
                           BigDecimal oldPrice, BigDecimal newPrice,
                           PriceChangeReason reason, Long purchaseRecordId,
                           String createdBy, LocalDateTime createdAt) {
        this(id, targetType, productId, productName, listingId, listingName, platform, masterProductId,
                optionId, optionName, oldPrice, newPrice,
                (oldPrice == null || newPrice == null) ? null : newPrice.subtract(oldPrice),
                reason, purchaseRecordId, createdBy, createdAt);
    }
}
