package com.pms.service.listing;

import com.pms.domain.GeneratedProductData;
import com.pms.domain.MarketplaceAccount;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;

import java.math.BigDecimal;

/**
 * Per-platform channel adapter seam (FEATURE_2608_06 / 3c). The orchestration
 * ({@code ListingRegistrationService}) depends only on this interface — the NAVER adapter is added later (3d).
 *
 * <p><b>⚠️ Async principle (MUST-KEEP)</b>: {@link #register} returns quickly (seconds) with the market
 * product id; it does NOT wait for approval (hours~days). Approval is detected later by {@link #fetchStatus}
 * (manual refresh / sweep). Adapters do HTTP only — the cell state machine lives in the orchestration.</p>
 */
public interface ListingChannel {

    /** Platform key this adapter handles (e.g. "COUPANG"). Resolver matching key. */
    String platform();

    /**
     * Validate that the cell may be registered under this channel's own registration policy (FEATURE_2608_06 /
     * 63). A violation throws (e.g. {@code IllegalArgumentException} → 400). The orchestration delegates here
     * before {@link #register} — the channel owns the policy (Coupang: skip for AB / required-attribute check
     * for SINGLE; NAVER implements its own). The {@code gen} parameter is kept for contract symmetry with
     * {@link #register}/{@link #update} even when an implementation does not use it.
     *
     * @param cell the DRAFT channel cell
     * @param gen  generated assets (payload source; may be unused by an implementation)
     * @param acct the marketplace account (credentials)
     */
    void validateRegistrable(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct);

    /**
     * Whether the cell's resolved shipping config (channel ?? master ?? account default, 75) satisfies this
     * channel's register requirements (FEATURE_2608_06 / 77). Pure read — never throws; the register path
     * still enforces the same rules and 400s. Exposed as {@code GeneratedProductResponse.shippingReady} so
     * the UI can disable [마켓 등록] before the user hits that 400.
     *
     * <p>The required field set is platform-specific, so each adapter owns the judgement (Coupang delegates
     * to {@link com.pms.service.listing.shipping.ShippingReadiness}; NAVER brings its own in 3d).</p>
     *
     * @param cell the channel cell
     * @return true when the cell may be registered as far as shipping config goes
     */
    boolean isShippingReady(ProductListing cell);

    /**
     * Register the product on the market → returns the market product id (sellerProductId).
     * Does NOT wait for approval.
     *
     * @param cell the DRAFT channel cell
     * @param gen  generated assets (thumbnail URL + detail HTML) — the payload source
     * @param acct the marketplace account (credentials)
     * @return the market product id (Coupang sellerProductId)
     */
    String register(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct);

    /**
     * Fetch the current market status + per-option ids (1 call). See {@link FetchResult}.
     *
     * @param cell the already-registered cell (has platformProductId)
     * @param acct the marketplace account (credentials)
     * @return status + option ids
     */
    FetchResult fetchStatus(ProductListing cell, MarketplaceAccount acct);

    /**
     * Update the listing: rebuild the WHOLE product object → PUT (no incremental option add — approved
     * options cannot be added piecemeal on Coupang; the whole object is re-submitted for re-approval).
     *
     * @param cell the registered cell
     * @param gen  generated assets (payload source)
     * @param acct the marketplace account (credentials)
     */
    void update(ProductListing cell, GeneratedProductData gen, MarketplaceAccount acct);

    /**
     * Stop selling the listing (approved options cannot be physically deleted → stop-selling).
     *
     * @param cell the registered cell
     * @param acct the marketplace account (credentials)
     */
    void delete(ProductListing cell, MarketplaceAccount acct);

    /**
     * Change the selling price of ONE option that is already on the market (FEATURE_2609_19 / D4). Unlike
     * {@link #update} (whole object re-submitted for re-approval) this is a partial update that takes effect
     * immediately. A platform that has no such API (NAVER) keeps this default and throws — callers only invoke
     * it for options that carry a {@code platformOptionId} (= Coupang vendorItemId).
     *
     * @param option the channel option to reprice
     * @param price  the price to send, already normalised to whole won by the caller (D13)
     * @param acct   the marketplace account (credentials)
     */
    default void updateOptionPrice(ProductListingOption option, BigDecimal price, MarketplaceAccount acct) {
        throw new UnsupportedOperationException("가격 부분수정 미지원 플랫폼: " + platform());
    }
}
