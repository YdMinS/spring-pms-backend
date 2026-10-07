package com.pms.dto.response;

import com.pms.domain.Platform;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One "table vs measured" comparison row per category (FEATURE_2609_30 / 06 · PLAN D16).
 *
 * <p>🔴 <b>Both ratios share one basis</b>: {@link #currentRatio} is the table commission plus VAT and
 * {@link #measuredRatio} is the (fee + its VAT) ratio Coupang actually took. Subtracting without matching the
 * basis books the whole 10% VAT as a "commission gap" and puts every category on the list.
 *
 * <p>⚠️ {@link #suggestedRate} is <b>the value that will be stored</b>. {@code commission_rate} is
 * {@code DECIMAL(5,4)} (FEATURE_2610_06 / D17), the same 4 decimal places as {@link #measuredRate}, so the two
 * are equal — no whole-percent rounding.
 *
 * @param currentRate   table value (VAT <b>excluded</b>) — null when the seed is missing
 * @param currentRatio  {@code currentRate × (1 + fee VAT rate)} — null when the seed is missing
 * @param measuredRatio measured = {@code Σ(fee + fee VAT) / Σ sale amount} (weighted average)
 * @param measuredRate  measured ratio turned back to VAT-excluded = {@code measuredRatio ÷ (1 + VAT rate)}
 * @param suggestedRate value stored on apply = {@code measuredRate} at 4 decimal places
 * @param gap           {@code measuredRatio − currentRatio} — null when the seed is missing
 * @param impact        {@code |gap| × sale amount} = sort key (largest money impact first)
 * @param samples       lines in the aggregate (refund lines excluded)
 * @param listingCount  distinct cells sold in this category — for the "affected cells" notice on apply
 */
public record CommissionSuggestionView(
        Long platformCategoryId,
        String platformCategoryCode,
        String platformCategoryName,
        Platform platform,
        BigDecimal currentRate,
        BigDecimal currentRatio,
        BigDecimal measuredRatio,
        BigDecimal measuredRate,
        BigDecimal suggestedRate,
        BigDecimal gap,
        BigDecimal impact,
        int samples,
        int listingCount,
        BigDecimal saleAmount,
        LocalDate periodFrom,
        LocalDate periodTo
) {
}
