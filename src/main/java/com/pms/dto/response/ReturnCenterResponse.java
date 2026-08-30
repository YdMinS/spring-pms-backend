package com.pms.dto.response;

import com.pms.service.listing.shipping.ReturnCenter;

import java.math.BigDecimal;

/**
 * Response for one return center (FEATURE_2608_06 / 72) — same fields as the normalized
 * {@link ReturnCenter} (full address block). Built via {@link #from} (mirrors {@code CategoryNodeResponse.from}).
 */
public record ReturnCenterResponse(
        String code,
        String name,
        String chargeName,
        String contactNumber,
        String zipCode,
        String address,
        String addressDetail,
        BigDecimal returnCharge,
        BigDecimal deliveryChargeOnReturn) {

    public static ReturnCenterResponse from(ReturnCenter center) {
        return new ReturnCenterResponse(
                center.code(),
                center.name(),
                center.chargeName(),
                center.contactNumber(),
                center.zipCode(),
                center.address(),
                center.addressDetail(),
                center.returnCharge(),
                center.deliveryChargeOnReturn());
    }
}
