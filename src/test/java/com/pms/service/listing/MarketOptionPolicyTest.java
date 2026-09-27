package com.pms.service.listing;

import com.pms.domain.ListingStatus;
import com.pms.domain.OptionApprovalStatus;
import com.pms.domain.Platform;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** The single "is this channel option on the market?" judgement (FEATURE_2609_74). Pure — no Mockito. */
class MarketOptionPolicyTest {

    private ProductListing cell(String platformProductId, ListingStatus status) {
        return ProductListing.builder().id(1L).platform(Platform.COUPANG).name("셀")
                .platformProductId(platformProductId).status(status).build();
    }

    private ProductListingOption option(boolean active, String platformOptionId) {
        return ProductListingOption.builder().id(10L).optionName("2세트").sellingPrice(BigDecimal.TEN)
                .active(active).platformOptionId(platformOptionId)
                .approvalStatus(OptionApprovalStatus.NOT_APPROVED).build();
    }

    @Test
    void carriedOnMarket_onMarketCellActiveOption_true() {
        assertThat(MarketOptionPolicy.carriedOnMarket(cell("P-1", ListingStatus.SELLING), option(true, null)))
                .isTrue();
    }

    @Test
    void carriedOnMarket_draftCell_false() {
        assertThat(MarketOptionPolicy.carriedOnMarket(cell(null, ListingStatus.DRAFT), option(true, "V-1")))
                .isFalse();
    }

    @Test
    void awaitingMarketId_onMarketCellWithoutOptionId_true() {
        assertThat(MarketOptionPolicy.awaitingMarketId(cell("P-1", ListingStatus.SUBMITTED), option(true, null)))
                .isTrue();
    }

    @Test
    void awaitingMarketId_draftCell_false() {
        assertThat(MarketOptionPolicy.awaitingMarketId(cell(null, ListingStatus.DRAFT), option(true, null)))
                .isFalse();
    }

    // D33: partial approval is SELLING too — an option without an id there stays locked.
    @Test
    void nameLocked_sellingCellWithoutOptionId_true() {
        assertThat(MarketOptionPolicy.nameLocked(cell("P-1", ListingStatus.SELLING), option(true, null)))
                .isTrue();
    }

    // D32: a REJECTED cell unlocks.
    @Test
    void nameLocked_rejectedCell_false() {
        assertThat(MarketOptionPolicy.nameLocked(cell("P-1", ListingStatus.REJECTED), option(true, null)))
                .isFalse();
    }
}
