package com.pms.domain;

/**
 * Where the goods physically are (PLAN 2609_28 D16).
 *
 * <p>Only {@code OWN} exists today — 3PL is not in use. It is an enum (not a hardcoded constant)
 * so a third-party warehouse becomes an ADDITIVE value later instead of a schema change, and the
 * column costs nothing on a table we are creating anyway.
 *
 * <p>⚠️ Never write {@code StockLocation.OWN} at a call site — go through
 * {@code StockLedgerServiceImpl#resolveLocation} so the fulfilment-path decision stays in one
 * function (D17).
 */
public enum StockLocation {
    OWN
}
