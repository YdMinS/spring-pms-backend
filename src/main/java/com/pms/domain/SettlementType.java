package com.pms.domain;

/**
 * Payout cycle the marketplace assigned to a payout unit (FEATURE_2609_30 / PLAN D5-3).
 *
 * <p>⚠️ {@link #DAILY} is NOT in Coupang's documented enum list — the documented values are
 * MONTHLY/WEEKLY/ADDITIONAL/RESERVE — but their own sample response returns {@code DAILY}.
 * Coupang's docs have been wrong before (see {@code reference_coupang_shipping_place_lookup_api}),
 * so unknown values are absorbed as {@link #UNKNOWN} rather than thrown: a value we have never seen
 * must never stop a settlement load, because the money already moved.
 *
 * <p>{@link #RESERVE} = "payment of the final amount" (paying out the remaining balance), not a
 * release of withheld reserve.
 */
public enum SettlementType {

    DAILY,
    WEEKLY,
    MONTHLY,
    ADDITIONAL,
    RESERVE,
    UNKNOWN;

    /** Maps a raw platform value, absorbing anything unknown (never throws). */
    public static SettlementType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        for (SettlementType type : values()) {
            if (type.name().equalsIgnoreCase(raw.trim())) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
