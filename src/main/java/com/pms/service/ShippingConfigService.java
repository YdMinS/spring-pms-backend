package com.pms.service;

import com.pms.dto.request.ShippingConfigRequest;
import com.pms.dto.response.ShippingConfigResponse;
import com.pms.service.listing.shipping.OutboundPlace;
import com.pms.service.listing.shipping.ReturnCenter;

import java.util.List;

/**
 * Shipping management for a marketplace account (FEATURE_2608_06 / 72): lookup-first outbound places / return
 * centers (platform lookup when supported, else an empty list = manual entry) + upsert of the per-account
 * shipping config. Lookup-picked values and manually-entered values take the same save path.
 *
 * <p>Scope = outbound/return lookup seam + config persistence. Consumed by register (73), which is the final
 * guard for missing required values.</p>
 */
public interface ShippingConfigService {

    /**
     * List the outbound places for the account's platform.
     *
     * @param accountId the marketplace account id (404 if absent)
     * @return the outbound places; empty when the platform has no lookup or the fetch is empty (→ manual entry)
     */
    List<OutboundPlace> listOutbound(Long accountId);

    /**
     * List the return centers (full address blocks) for the account's platform.
     *
     * @param accountId the marketplace account id (404 if absent)
     * @return the return centers; empty when the platform has no lookup or the fetch is empty (→ manual entry)
     */
    List<ReturnCenter> listReturn(Long accountId);

    /**
     * Get the account's shipping config.
     *
     * @param accountId the marketplace account id (404 if absent)
     * @return the stored config, or an all-null response when none is stored yet
     */
    ShippingConfigResponse getConfig(Long accountId);

    /**
     * Upsert the account's shipping config (updates the existing one or creates a new one).
     *
     * @param accountId the marketplace account id (404 if absent)
     * @param request   the config values (partial allowed)
     * @return the saved config
     */
    ShippingConfigResponse upsertConfig(Long accountId, ShippingConfigRequest request);
}
