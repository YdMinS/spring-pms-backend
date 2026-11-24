package com.pms.service.stock;

/**
 * Which claim work a lookup or a save is for (FEATURE_2611_34): picks the ledger sum and the messages.
 * {@code RETURN_IN} = 「반품 재입고」 · {@code EXCHANGE_SHIP} = 「교환 발송」.
 */
public enum ClaimWorkKind {
    RETURN_IN,
    EXCHANGE_SHIP
}
