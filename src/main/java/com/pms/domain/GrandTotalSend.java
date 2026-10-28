package com.pms.domain;

/**
 * Whether a Coupang listing sends its stored 총 수량 when it is revised (FEATURE_2610_34 / D6 · D11 · D16 · D22).
 *
 * <p>Set by the new registration and by later revisions; only the revision (PUT) path decides on it. A stored 총 수량
 * value is never changed — only whether it is put in the payload.</p>
 */
public enum GrandTotalSend {

    /** Sent. Pre-existing listings (changeset 120 default) and a registration that passed with it (D22). */
    SENT,

    /** Left out — the registration or a revision passed without it (D6 · D11). */
    OMITTED,

    /** Sent and never dropped again — a revision without it was rejected for a missing 총 수량 (D11 · D16). */
    SENT_CONFIRMED
}
