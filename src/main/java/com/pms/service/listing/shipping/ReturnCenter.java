package com.pms.service.listing.shipping;

import java.math.BigDecimal;

/**
 * Normalized return center (FEATURE_2608_06 / 72) — platform-agnostic, the <b>full address block</b>.
 *
 * <p>Coupang product registration needs every field of the chosen return center (code + charge name,
 * contact, zip, address, address detail, return charge, delivery charge on return), so the lookup fetches
 * the whole block and it is stored as a unit on selection — never just the code.</p>
 */
public record ReturnCenter(
        String code,
        String name,
        String chargeName,
        String contactNumber,
        String zipCode,
        String address,
        String addressDetail,
        BigDecimal returnCharge,
        BigDecimal deliveryChargeOnReturn) {
}
