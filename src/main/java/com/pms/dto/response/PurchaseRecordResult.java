package com.pms.dto.response;

/**
 * Result of one check-in (PLAN 2609_29 D1 · D17 · D19 · FEATURE_2610_09 D65).
 *
 * <p>⚠️ {@code stockRecorded=false} has three causes — a negative correction (D17), {@code recordStock=false}
 * (D19) or the stock link setting is off (FEATURE_2610_09 D65). The reasons are not split into fields: a split
 * field would split the screens too.
 */
public record PurchaseRecordResult(Long purchaseRecordId, boolean stockRecorded) {}
