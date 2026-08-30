package com.pms.service.listing.shipping;

import java.math.BigDecimal;

/**
 * Fully-resolved shipping config for one channel cell (FEATURE_2608_06 / 75) — the concrete values after the
 * field-wise 3-level resolution ({@code channel ?? master ?? account default}), produced by
 * {@link ShippingConfigResolver}. Same field set as {@code MarketplaceShippingConfig} plus
 * {@code extraInfoMessage}, all typed. The register adapter reads this record only (never the raw entity or
 * the override maps).
 *
 * <p>Outbound place + return center are resolved {@code channel ?? account} only (the master step is skipped
 * — those keys are dropped from the master whitelist). Everything else (carrier code included) is 3-level.</p>
 */
public record ResolvedShippingConfig(
        // outbound place (channel ?? account)
        String outboundShippingPlaceCode,
        // return center full address block (channel ?? account)
        String returnCenterCode,
        String returnChargeName,
        String returnContactNumber,
        String returnZipCode,
        String returnAddress,
        String returnAddressDetail,
        BigDecimal returnCharge,
        BigDecimal deliveryChargeOnReturn,
        // delivery settings (channel ?? master ?? account)
        String deliveryMethod,
        String deliveryCompanyCode,
        String deliveryChargeType,
        BigDecimal deliveryCharge,
        BigDecimal freeShipOverAmount,
        String remoteAreaDeliverable,
        String unionDeliveryType,
        String extraInfoMessage) {
}
