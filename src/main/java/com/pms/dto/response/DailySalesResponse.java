package com.pms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One sale date of the dashboard sales chart (FEATURE_2610_29 D22). The list has one row per date of the period,
 * a date without sales included with zeros.
 *
 * @param date       sale date (the order's {@code orderedAt} date — the axis of the sales screens)
 * @param grossSales sales of that date, the same amount as {@code SellerSalesResponse.grossSales}
 * @param orderCount orders of that date that are in that amount (an order cancelled in full is not)
 */
public record DailySalesResponse(LocalDate date, BigDecimal grossSales, long orderCount) {
}
