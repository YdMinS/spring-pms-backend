package com.pms.service.listing;

import com.pms.domain.ListingMarketValueGroup;
import com.pms.dto.response.ListingMarketValuesResponse;

import java.util.Set;

/**
 * The 「마켓 값 사용」 groups of a channel cell (FEATURE_2611_27 / D17 ~ D20 · D27 · D29). The one place that stores the
 * values a market sends from the cell's own values (attributes · notices, search tags, shipping) and turns their flags
 * on or off.
 *
 * <p>⚠️ Nothing is sent to the market, and {@code needsMarketSync} is never touched.</p>
 */
public interface ListingMarketValueService {

    /**
     * Store the groups of {@code fetched} that this cell's market sends from the cell's own values
     * ({@link ListingChannel#marketValueGroups()}) and whose read carries a value, and set the three flags to that
     * judgement (R3 · R4). A group that stays off keeps the cell's values as they are (D29).
     *
     * @param listingId the channel cell id (absent / other tenant = 404)
     * @param fetched   the product as read from the market
     * @return the groups turned on
     * @throws IllegalArgumentException when the shipping save refuses the values — the whole call rolls back
     */
    Set<ListingMarketValueGroup> applyImported(Long listingId, ImportedProduct fetched);

    /**
     * [마스터 값으로 바꾸기] (R6 · R7): clear the cell's own values of {@code group} and turn its flag off, so the
     * group follows the master again.
     *
     * @param listingId the channel cell id (absent / other tenant = 404)
     * @param group     the group to switch back
     * @return the three flags after the switch
     * @throws IllegalArgumentException when the group's flag is off, or when {@code ATTRIBUTES} is asked on a cell that
     *                                  uses its own category (400)
     */
    ListingMarketValuesResponse useMasterValues(Long listingId, ListingMarketValueGroup group);
}
