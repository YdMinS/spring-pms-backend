package com.pms.service.listing;

import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.service.elevenst.ElevenstListingRules;
import com.pms.service.esm.EsmText;

/**
 * The name a channel option takes when it copies its master option's name — channel creation, master rename
 * follow-up, [옵션명 일괄 적용], [마스터 옵션명 반영], back-to-auto. One place for every marketplace rule:
 * an 11st cell = the 11st-safe form (FEATURE_2610_13 / D66, {@link ElevenstListingRules#nameForCell}), an ESM cell
 * = cut at {@link EsmText#OPTION_NAME_BYTES} bytes (FEATURE_2610_26 / D26), every other cell = the master name as is.
 */
public final class ListingOptionNames {

    private ListingOptionNames() {
    }

    public static String nameForCell(ProductListing cell, String masterName) {
        if (cell != null && cell.getPlatform() == Platform.ESM) {
            return EsmText.cutBytes(masterName, EsmText.OPTION_NAME_BYTES);
        }
        return ElevenstListingRules.nameForCell(cell, masterName);
    }
}
