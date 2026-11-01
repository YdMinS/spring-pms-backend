package com.pms.service.stock;

import com.pms.domain.Seller;
import com.pms.domain.StockMovement;

import java.math.BigDecimal;

/**
 * One ledger row to write for an outgoing or returned movement (FEATURE_2610_19 D42 · D48 ④ · D139 ⑤).
 *
 * @param source    the lot the row uses (outgoing) or goes back into (matched return); null = no lot
 * @param seller    the row's seller column — the lot owner, null for an undecided lot (D124)
 * @param quantity  positive piece count; the caller applies the sign
 * @param unitPrice only for a return that becomes a new lot (D139 ③ ④ · D140 · D142); null otherwise
 */
public record LotSlice(StockMovement source, Seller seller, int quantity, BigDecimal unitPrice) {
}
