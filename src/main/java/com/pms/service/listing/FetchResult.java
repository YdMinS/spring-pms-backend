package com.pms.service.listing;

import com.pms.domain.ListingStatus;

import java.util.List;

/**
 * Result of {@link ListingChannel#fetchStatus} (FEATURE_2608_06 / 3c): the cell's market status plus, per
 * option, the ids issued by the market once approved.
 *
 * <p>Matching key = {@link OptionId#optionName()} (option names are unique within a cell). The orchestration
 * ({@code ListingRegistrationService.fetchStatus}) matches these against the cell's options by name; unmatched
 * options keep {@code NOT_APPROVED}.</p>
 *
 * @param status  the cell status mapped from the market statusName
 * @param options per-option ids parsed from the market response (empty until approved)
 */
public record FetchResult(ListingStatus status, List<OptionId> options) {

    /**
     * A single option's market ids.
     *
     * @param optionName          matching key against the cell's option name
     * @param vendorItemId        Coupang vendorItemId → stored as {@code platformOptionId} (order mapping)
     * @param sellerProductItemId Coupang sellerProductItemId → stored for option updates
     */
    public record OptionId(String optionName, String vendorItemId, String sellerProductItemId) {
    }
}
