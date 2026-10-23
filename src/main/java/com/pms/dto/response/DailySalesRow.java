package com.pms.dto.response;

import java.math.BigDecimal;

/**
 * Sales of one sale date (FEATURE_2610_29 D22) — the {@code OrderLineRepository.aggregateDailySales} projection.
 *
 * <p>⚠️ Constructor projection: {@code year()}/{@code month()}/{@code day()} come back as integers, the amount as
 * {@code BigDecimal} and {@code count(distinct …)} as {@code Long} — the field types must match exactly.</p>
 *
 * @param grossSales {@code Σ (unitPrice × netQty)} — the same amount as {@code SalesLineGroup.grossSales}
 * @param orderCount orders with at least one line whose {@code orderQty > cancelQty}
 */
public record DailySalesRow(int year, int month, int day, BigDecimal grossSales, Long orderCount) {
}
