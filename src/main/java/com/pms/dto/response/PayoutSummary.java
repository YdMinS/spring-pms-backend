package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Summary of one payout (FEATURE_2609_30 / 02 · PLAN D3 · D5-3).
 *
 * <p>The list, the detail and the report share this summary — sending different fields per screen produces "why do
 * the list and the detail show different amounts".
 *
 * <p>⚠️ The owning axis is the <b>channel (marketplaceAccount)</b>. The seller is for display; settlement arrives per
 * vendorId (D3).
 * ⚠️ {@code lineCount == 0} is normal (D5-4) — reserve releases, debt repayments and ad settlements carry no lines.
 *
 * @param paymentCheckNeeded FEATURE_2610_20 / D40: an 11st payout still {@code SCHEDULED} after its remittance date
 *                           (before today, KST) — the screens show 「입금 확인 필요」 instead of 「예정」. Always false
 *                           for other platforms. Display only: the stored status stays {@code SCHEDULED}.
 */
public record PayoutSummary(
        Long payoutId,
        Long accountId,
        String platform,
        String accountAlias,
        Long sellerId,
        String sellerName,
        String settlementType,
        String revenueRecognitionMonth,
        LocalDate recognitionFrom,
        LocalDate recognitionTo,
        LocalDate settlementDate,
        LocalDate finalSettlementDate,
        BigDecimal totalSale,
        BigDecimal serviceFee,
        BigDecimal finalAmount,
        String status,
        String reconStatus,
        long lineCount,
        boolean paymentCheckNeeded) {
}
