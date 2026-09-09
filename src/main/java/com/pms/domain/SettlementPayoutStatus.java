package com.pms.domain;

/**
 * Whether a payout unit has actually been paid (FEATURE_2609_30 / PLAN D5).
 *
 * <p>Coupang's payment-history status values (DONE / SUBJECT) are mapped by prompt 02 — unknown
 * values are absorbed as {@link #UNKNOWN}, same reasoning as {@link SettlementType}.
 */
public enum SettlementPayoutStatus {

    SCHEDULED,
    PAID,
    UNKNOWN;

    public static SettlementPayoutStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        for (SettlementPayoutStatus status : values()) {
            if (status.name().equalsIgnoreCase(raw.trim())) {
                return status;
            }
        }
        return UNKNOWN;
    }
}
