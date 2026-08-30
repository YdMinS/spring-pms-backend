package com.pms.service.listing.shipping;

import com.pms.domain.MarketplaceAccount;

import java.util.List;

/**
 * Per-platform shipping-place lookup seam (FEATURE_2608_06 / 72) — fetch the outbound places / return centers
 * already registered on the marketplace so the user can pick one (lookup-first). Mirrors the
 * {@code CategoryLookup} / {@code ListingChannel} resolver pattern: the service depends only on this interface;
 * {@link CoupangShippingPlaceProvider} implements it and the NAVER adapter joins later (seat only).
 *
 * <p>⚠️ Unlike {@code CategoryLookup}, a missing provider is <b>not fatal</b> — platforms without a lookup
 * (or a failed/empty fetch) fall back to manual entry, so {@code ShippingPlaceProviderResolver} returns an
 * {@code Optional} rather than throwing. A fetch that fails or returns nothing yields an empty list (never an
 * exception), the same posture as {@code CoupangCategoryLookup.predict}.</p>
 */
public interface ShippingPlaceProvider {

    /** Platform key this adapter handles (e.g. "COUPANG"). Resolver matching key. */
    String platform();

    /**
     * Fetch the outbound shipping places registered on the platform for this account.
     *
     * @param account the marketplace account (credentials for HMAC)
     * @return the outbound places (empty when none / on a parse failure — manual entry then applies)
     */
    List<OutboundPlace> fetchOutboundPlaces(MarketplaceAccount account);

    /**
     * Fetch the return centers (full address blocks) registered on the platform for this account.
     *
     * @param account the marketplace account (credentials for HMAC)
     * @return the return centers (empty when none / on a parse failure — manual entry then applies)
     */
    List<ReturnCenter> fetchReturnCenters(MarketplaceAccount account);
}
