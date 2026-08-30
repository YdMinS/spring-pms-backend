package com.pms.service.category;

import java.math.BigDecimal;
import java.util.List;

/**
 * One parsed Coupang category leaf row (FEATURE_2608_06 / 53).
 *
 * @param code      the mall leaf code (from the {@code [12345]} bracket) — never null (bracket-less rows are skipped).
 * @param feeRate   sales-agency commission as a <b>fraction</b> (e.g. {@code 0.106} = 10.6%); nullable when col B is blank.
 * @param segments  the full path split on {@code >}, trimmed (last = leaf name, preceding = intermediate names).
 */
public record ParsedLeaf(String code, BigDecimal feeRate, List<String> segments) {
}
