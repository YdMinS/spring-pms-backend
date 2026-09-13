package com.pms.service.stock;

import com.pms.dto.response.OutboundProductLine;

import java.util.List;

/**
 * What is still left to send out for ONE order line (FEATURE_2609_40 / PLAN D28).
 *
 * <p>🔴 This is the <b>seam</b> that lets the packing console ask the outbound owner for remaining
 * quantities instead of computing them again: {@code remaining = required − Σ STOCK_OUT}. Copying that
 * formula anywhere else means the packing screen and the outbound screen can disagree about how many
 * units are left — and the copy would almost certainly walk into the {@code cancelQty} trap the
 * {@link StockOutServiceImpl} class javadoc warns about (a return would silently shrink what is left
 * to ship).</p>
 *
 * <p>⚠️ {@code shipment_parcel_item} rows are NOT subtracted on top of this. Completing a parcel writes
 * the STOCK_OUT rows itself, so anything already packed is already gone from these numbers; subtracting
 * the packed items again would double-count them.</p>
 *
 * @param orderLineId the line
 * @param products    per product: required / confirmed / remaining. Empty when {@code failure != null}
 * @param failure     BOM expansion failure, or null. A failed line must not be packed — nobody knows
 *                    which goods it would consume
 */
public record RemainingLine(Long orderLineId, List<OutboundProductLine> products,
                            OrderLineExpander.Failure failure) {

    public boolean failed() {
        return failure != null;
    }

    /** Units still to send out across every product of this line. */
    public int totalRemainingQty() {
        return products.stream().mapToInt(OutboundProductLine::remainingQty).sum();
    }

    /** Units of one product still to send out (0 when the product does not belong to this line). */
    public int remainingQty(Long productId) {
        return products.stream()
                .filter(p -> p.productId().equals(productId))
                .mapToInt(OutboundProductLine::remainingQty)
                .findFirst()
                .orElse(0);
    }

    /** Units this line consumes in total (BOM × orderQty) — the denominator of the savings ratio (D4). */
    public int totalRequiredQty() {
        return products.stream().mapToInt(OutboundProductLine::requiredQty).sum();
    }
}
