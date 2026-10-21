package com.pms.service.category;

import java.math.BigDecimal;

/**
 * One row of the ESM+ fee table file (FEATURE_2610_26 / D14): 「대분류 | 중분류 | 오픈마켓」. {@code rate} is the
 * 「오픈마켓」 fraction as written (0.13 = 13%, VAT included — D62); {@code null} when the cell is not a number.
 *
 * @param major   the 「대분류」 text (a merged cell is read as its top value)
 * @param middle  the 「중분류」 text (「전체」 = the whole 「대분류」)
 * @param rate    the table value, or null
 * @param rawRate the cell text as read, for the result list
 */
public record EsmFeeRow(String major, String middle, BigDecimal rate, String rawRate) {
}
