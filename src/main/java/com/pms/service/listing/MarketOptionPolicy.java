package com.pms.service.listing;

import com.pms.domain.ListingStatus;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;

/**
 * One place for the question "is this channel option on a market?" (FEATURE_2609_74). No method here calls
 * {@code getPlatform()} — a Coupang cell and an 11st cell get the same answer (FEATURE_2610_13 / D107).
 *
 * <p>When the lock (delete guard) · name push-down · 「변경 미반영」 marker · rename lock each asked the same
 * question, the rules drifted — in fact the lock compared option names, so options imported from Coupang lost
 * their lock. Make these judgements here.</p>
 *
 * <p>❌ Do not judge by option name: Coupang option names come in mixed formats and Coupang may change them.</p>
 */
public final class MarketOptionPolicy {

    /** 2609_74/D32·D33: 400 message when a direct channel rename hits a name-locked option. One text for every status. */
    public static final String NAME_LOCKED_MESSAGE =
            "마켓 심사 중인 옵션은 이름을 바꿀 수 없습니다. [승인 새로고침]으로 결과를 받은 뒤 바꾸세요";

    private MarketOptionPolicy() {
    }

    /** The option is on a market or goes with the next send — one of: active · has an option id · approved before. */
    public static boolean onMarket(ProductListingOption option) {
        return Boolean.TRUE.equals(option.getActive()) || option.isMarketRegistered();
    }

    /**
     * The option belongs to a cell put on a market — any platform with a product id, Coupang, 11st or ESM
     * (FEATURE_2610_13 / D121 · FEATURE_2610_26 / D65) — and is {@link #onMarket}: the condition for the delete lock
     * and 「변경 미반영」.
     */
    public static boolean carriedOnMarket(ProductListing cell, ProductListingOption option) {
        return cell.getPlatformProductId() != null && onMarket(option);
    }

    /**
     * The option belongs to a cell put on a market (Coupang or 11st, FEATURE_2610_13 / D107) but has no option id
     * yet. The approval result finds such an option <b>by name only</b>
     * ({@code ListingRegistrationServiceImpl.fetchStatus}), so renaming it breaks the match.
     */
    public static boolean awaitingMarketId(ProductListing cell, ProductListingOption option) {
        return cell.getPlatformProductId() != null && option.getPlatformOptionId() == null;
    }

    /**
     * 2609_74/D32·D33: a person may not rename this channel option — it is {@link #awaitingMarketId} and the
     * cell is not REJECTED (SUBMITTED · SELLING incl. partial approval · SUSPENDED all lock). A REJECTED cell
     * unlocks: the name is fixed and sent again with [수정 요청]. The status is the last [승인 새로고침] result —
     * Coupang is never asked separately. Used by the three human rename paths only; the automatic master
     * rename propagation keeps {@link #awaitingMarketId} (D25 — status-independent).
     */
    public static boolean nameLocked(ProductListing cell, ProductListingOption option) {
        return awaitingMarketId(cell, option) && cell.getStatus() != ListingStatus.REJECTED;
    }
}
