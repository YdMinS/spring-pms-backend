package com.pms.dto.response;

/**
 * Ledger sum per (claim × product) for one movement type and reason — internal (FEATURE_2611_34 D20 · D42).
 *
 * <p>⚠️ {@code quantity} keeps the stored sign (an exchange shipment is negative); the service flips it.
 */
public record ClaimProductSumView(Long orderClaimId, Long productId, long quantity) {
}
