package com.pms.service.listing;

import com.pms.domain.ListingUpdateItem;

import java.util.List;
import java.util.Set;

/**
 * What a Coupang revision overwrites on the product it reads from Coupang (FEATURE_2611_25 / D4 · D9 · D24 · D26 ·
 * D29 · D31). Markets that do not compare before an update ignore it (D21).
 *
 * @param overwrite         the items sent with the oclyx value — every other item is sent as read from Coupang
 * @param defaultOptions    true = send every option line that is active now (D24 · D29); false = only
 *                          {@code addOptionIds}
 * @param addOptionIds      the option lines to send when {@code defaultOptions} is false (D31 {@code addOptions})
 * @param skipWhenUnchanged true = no PUT when every overwritten item already equals Coupang and no option line is sent
 *                          (batch push, D26)
 * @param savesOptionChoice true = a passed revision reports the option lines' on/off to store (D31 · R12)
 */
public record ListingUpdateSelection(Set<ListingUpdateItem> overwrite, boolean defaultOptions, Set<Long> addOptionIds,
                                     boolean skipWhenUnchanged, boolean savesOptionChoice) {

    /** The batch push (D4 · D9 · D24 · D26 · D29) — the on/off of options is never changed (D31). */
    public static ListingUpdateSelection batch() {
        return new ListingUpdateSelection(ListingUpdateItem.BATCH_DEFAULT, true, Set.of(), true, false);
    }

    /**
     * The [수정 요청] (R3 · D11 · D31). {@code overwrite} null = the D9 items, an empty list = none; {@code addOptions}
     * null = every option line that is active now, an empty list = none.
     */
    public static ListingUpdateSelection request(List<ListingUpdateItem> overwrite, List<Long> addOptions) {
        Set<ListingUpdateItem> items = overwrite == null ? ListingUpdateItem.BATCH_DEFAULT : Set.copyOf(overwrite);
        return addOptions == null
                ? new ListingUpdateSelection(items, true, Set.of(), false, true)
                : new ListingUpdateSelection(items, false, Set.copyOf(addOptions), false, true);
    }

    /** Whether the option line of {@code optionId} is sent (D24 · D29 · D31). */
    public boolean sends(Long optionId, boolean active) {
        return defaultOptions ? active : addOptionIds.contains(optionId);
    }
}
