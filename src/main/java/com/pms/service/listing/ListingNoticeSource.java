package com.pms.service.listing;

import com.pms.domain.MasterProduct;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;

import java.util.Map;

/**
 * FEATURE_2611_27 / D30 · R12: the notice a market that sends one product-level notice (11st · LotteON) converts —
 * the listing's own group and its first active option's notices while the listing uses the market values, otherwise
 * the master's.
 *
 * <p>The one place that decides where the sent notice comes from; the market adapters only call {@link #of} and pass
 * the result to their own notice converter.</p>
 *
 * <pre>{@code
 * ListingNoticeSource.NoticeInput input = ListingNoticeSource.of(cell, lines.isEmpty() ? null : lines.get(0).option());
 * ElevenstNoticeConverter.convert(input.group(), input.notices());
 * }</pre>
 *
 * <p>❌ Do not re-check {@code usesMarketAttributes} in an adapter — call this.</p>
 */
public final class ListingNoticeSource {

    /** The notice group and values to convert (either may be null). */
    public record NoticeInput(String group, Map<String, String> notices) {
    }

    private ListingNoticeSource() {
    }

    /**
     * @param cell               the listing being sent
     * @param firstActiveOption  the first active listing option (null when none)
     */
    public static NoticeInput of(ProductListing cell, ProductListingOption firstActiveOption) {
        if (cell.isUsesMarketAttributes()) {
            return new NoticeInput(cell.getCategoryNoticeGroup(),
                    firstActiveOption == null ? null : firstActiveOption.getCategoryNotices());
        }
        MasterProduct master = cell.getMasterProduct();
        return new NoticeInput(master == null ? null : master.getCategoryNoticeGroup(),
                master == null ? null : master.getCategoryNotices());
    }
}
