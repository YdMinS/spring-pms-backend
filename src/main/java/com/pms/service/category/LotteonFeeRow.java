package com.pms.service.category;

import java.math.BigDecimal;

/**
 * One line of the LotteON commission CSV (FEATURE_2610_24 / D46).
 *
 * @param path    standard category names joined with 「 > 」 (e.g. 「e쿠폰/티켓 > 모바일금액권」)
 * @param percent the commission percent, VAT included (e.g. 7.00)
 */
public record LotteonFeeRow(String path, BigDecimal percent) {
}
