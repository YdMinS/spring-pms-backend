package com.pms.service;

import com.pms.dto.request.ShippingConfigRequest;
import com.pms.dto.response.ElevenstShippingConfigResponse;
import com.pms.dto.response.EsmChoiceResponse;
import com.pms.dto.response.EsmShippingConfigResponse;
import com.pms.dto.response.ShippingConfigResponse;
import com.pms.service.listing.shipping.OutboundPlace;
import com.pms.service.listing.shipping.ReturnCenter;

import java.util.List;
import java.util.Map;

/**
 * Shipping management for a marketplace account (FEATURE_2608_06 / 72): lookup-first outbound places / return
 * centers (platform lookup when supported, else an empty list = manual entry) + upsert of the per-account
 * shipping config. Lookup-picked values and manually-entered values take the same save path.
 *
 * <p>Scope = outbound/return lookup seam + config persistence — and, for an ESM sales channel, the lookups of the
 * ESM choices (bundle-fee policies of a place, dispatch policies, couriers; FEATURE_2610_26 / D37). Consumed by
 * register (73), which is the final guard for missing required values.</p>
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

    /**
     * FEATURE_2610_13 / D84: the 11st shipping settings of an 11st sales channel (empty map when none yet).
     *
     * @throws IllegalArgumentException (→ 400) when the account is not on 11st
     */
    ElevenstShippingConfigResponse getElevenstConfig(Long accountId);

    /**
     * FEATURE_2610_13 / D84 · D106: replace the 11st shipping settings (only {@code elevenst.*} keys kept;
     * quantity tiers must chain).
     *
     * @throws IllegalArgumentException (→ 400) when the account is not on 11st or the tiers do not chain
     */
    ElevenstShippingConfigResponse upsertElevenstConfig(Long accountId, Map<String, String> settings);

    /**
     * FEATURE_2610_26 / D36: the ESM shipping settings of an ESM sales channel (empty map when none yet).
     *
     * @throws IllegalArgumentException (→ 400) when the account is not on ESM
     */
    EsmShippingConfigResponse getEsmConfig(Long accountId);

    /**
     * FEATURE_2610_26 / D36 · D60 · D66: replace the ESM shipping settings — the six input keys kept, the chosen
     * bundle-fee policy checked against its shipping place on ESM and its four facts stored with it.
     *
     * @throws IllegalArgumentException (→ 400) when the account is not on ESM, the policy is not the place's, or the
     *         return fee is not a whole number ≥ 0
     */
    EsmShippingConfigResponse upsertEsmConfig(Long accountId, Map<String, String> settings);

    /** FEATURE_2610_26 / D37: the bundle-fee policies of one shipping place of an ESM sales channel. */
    List<EsmChoiceResponse> listEsmBundlePolicies(Long accountId, String placeNo);

    /** FEATURE_2610_26 / D37 · D68: the dispatch policies of an ESM sales channel. */
    List<EsmChoiceResponse> listEsmDispatchPolicies(Long accountId);

    /** FEATURE_2610_26 / D37: the couriers ESM accepts. */
    List<EsmChoiceResponse> listEsmDeliveryCompanies(Long accountId);
}
