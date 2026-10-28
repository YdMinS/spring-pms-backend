package com.pms.service.listing;

import com.pms.domain.ListingStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Result of {@link ListingChannel#fetchStatus} (FEATURE_2608_06 / 3c): the cell's market status plus, per
 * option, the ids issued by the market once approved, (FEATURE_2610_27) the prices the same call returned and
 * (FEATURE_2610_36) the Coupang bundle type it returned.
 *
 * <p>Matching key = {@link OptionId#optionName()} (option names are unique within a cell). The orchestration
 * ({@code ListingRegistrationService.fetchStatus}) matches these against the cell's options by name; unmatched
 * options keep {@code NOT_APPROVED}.</p>
 *
 * @param status  the cell status mapped from the market statusName
 * @param options per-option ids parsed from the market response (empty until approved)
 * @param statusName the market's own status text (null when the adapter did not read it)
 * @param prices the prices the same status call already returned (FEATURE_2610_27 / D13 · D16) — handed to
 *               {@link ListingChannel#readMarketPrices} so the status call is not repeated; null when the adapter did
 *               not read them
 * @param marketBundleType FEATURE_2610_36 / D10 ③: the Coupang {@code bundleInfo.bundleType} ({@code SINGLE} ·
 *               {@code AB}) the same call returned; null on every other market or when absent
 */
public record FetchResult(ListingStatus status, List<OptionId> options, String statusName, Prices prices,
                          String marketBundleType) {

    /** 2609_74 이전의 2인자 형태를 쓰는 호출부(테스트)를 위한 편의 생성자 — {@code statusName = null}. */
    public FetchResult(ListingStatus status, List<OptionId> options) {
        this(status, options, null, null);
    }

    /** The 3-argument form used before FEATURE_2610_27 — {@code prices = null}. */
    public FetchResult(ListingStatus status, List<OptionId> options, String statusName) {
        this(status, options, statusName, null);
    }

    /** The 4-argument form used before FEATURE_2610_36 — {@code marketBundleType = null}. */
    public FetchResult(ListingStatus status, List<OptionId> options, String statusName, Prices prices) {
        this(status, options, statusName, prices, null);
    }

    /**
     * A single option's market ids.
     *
     * @param optionName          matching key against the cell's option name
     * @param vendorItemId        Coupang vendorItemId → stored as {@code platformOptionId} (order mapping); 11st sends
     *                            our own option id here (FEATURE_2610_13 / D107 — the value sent as colSellerStockCd)
     * @param sellerProductItemId Coupang sellerProductItemId → stored for option updates
     */
    public record OptionId(String optionName, String vendorItemId, String sellerProductItemId) {
    }

    /**
     * Prices read by the status call (FEATURE_2610_27 / D16 · D37).
     *
     * @param base             11st {@code selPrc} · ESM Gmarket price; null on Coupang or when absent
     * @param auctionBase      ESM Auction price; null elsewhere or when absent
     * @param byMarketOptionId Coupang {@code vendorItemId} → {@code salePrice}; empty elsewhere
     */
    public record Prices(BigDecimal base, BigDecimal auctionBase, Map<String, BigDecimal> byMarketOptionId) {
    }
}
