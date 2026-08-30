package com.pms.service.listing.shipping;

/**
 * Normalized outbound shipping place (FEATURE_2608_06 / 72) — platform-agnostic.
 *
 * <p>An outbound place is where an order ships from; Coupang product registration requires its
 * {@code outboundShippingPlaceCode}. Only {@code code} (+ display {@code name}) is needed on selection —
 * unlike a return center, no address block is fetched (register carries only the code).</p>
 */
public record OutboundPlace(String code, String name) {
}
