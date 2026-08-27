package com.pms.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Shipping-override body (FEATURE_2608_06 / 75) for the master and channel (listing) PATCH endpoints.
 * {@code override} is a key → string map (whitelist = {@code ShippingOverrideKeys}); {@code null} or an empty
 * map clears the override (inherit). Value validation is deferred to register (72/73) — the service applies
 * the key whitelist only (no over-validation here).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShippingOverrideRequest {

    private Map<String, String> override;
}
