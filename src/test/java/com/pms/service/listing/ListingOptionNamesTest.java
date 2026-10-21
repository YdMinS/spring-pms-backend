package com.pms.service.listing;

import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** FEATURE_2610_26 / D26: an ESM cell cuts a copied master name at 50 bytes (Korean 2); others are unchanged. */
class ListingOptionNamesTest {

    private static ProductListing cell(Platform platform) {
        return ProductListing.builder().platform(platform).build();
    }

    @Test
    void esmCell_cutsAtFiftyBytes_neverReplacesCharacters() {
        String name = "가".repeat(30) + "&";

        assertThat(ListingOptionNames.nameForCell(cell(Platform.ESM), name)).isEqualTo("가".repeat(25));
        assertThat(ListingOptionNames.nameForCell(cell(Platform.ESM), "A&B")).isEqualTo("A&B");
    }

    @Test
    void coupangCell_keepsTheMasterName_elevenstCell_takesTheElevenstSafeForm() {
        assertThat(ListingOptionNames.nameForCell(cell(Platform.COUPANG), "가".repeat(30))).isEqualTo("가".repeat(30));
        assertThat(ListingOptionNames.nameForCell(cell(Platform.ELEVENST), "A&B")).isEqualTo("A B");
    }
}
