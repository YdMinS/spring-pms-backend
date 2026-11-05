package com.pms.service.listing;

import com.pms.dto.response.MarketSyncResponse;

/**
 * 「마켓 기준으로 맞추기」 (FEATURE_2611_11 / D9 · D10 · D11 · D12): read the product as it is on the market now and put
 * its four values on the listing at once — 「노출상품명」, 「등록상품명」, every photo (copied into our storage) and the detail
 * page. Values set by hand or generated are replaced too (D11).
 *
 * <p>🔴 Runs only when a person presses the button (D10) — [승인 새로고침] and the 02:05 batch never call it. It writes
 * nothing to the market (pushing stays [수정 요청], D16).</p>
 *
 * <p>Rules shared by the sync and the import ({@link #applyImported}):</p>
 * <ul>
 *   <li>「노출상품명」 = the market's display name (a blank one keeps ours — the column is NOT NULL).</li>
 *   <li>「등록상품명」 = the market's 「등록상품명」, or null (automatic) when the market has none — markets with one product
 *       name (11st · ESM) have none (D19).</li>
 *   <li>Detail = the market's HTML with origin {@code MARKET} (D2 · D23); a market without one keeps ours.</li>
 *   <li>Photos (D8 · D21 · D36 · D37 · D38): every photo is copied; the set replaces the stored one; the thumbnail
 *       becomes the first main photo in market order (Coupang: the first option's) with origin {@code MARKET}. When the
 *       market returned no photo, or the photo read / a copy failed, the photos are left as they were and the reason
 *       is returned (D35).</li>
 * </ul>
 */
public interface ListingMarketSyncService {

    /**
     * [「마켓 기준으로 맞추기」] for one listing. 404 when the listing is not this tenant's, 400 when it is not on a market.
     */
    MarketSyncResponse syncFromMarket(Long listingId);

    /**
     * The same rules applied to a product an import has just read (D2 · D4 · D6) — no second market call.
     */
    MarketSyncResponse applyImported(Long listingId, ImportedProduct imported);
}
