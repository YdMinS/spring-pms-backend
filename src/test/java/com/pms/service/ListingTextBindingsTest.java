package com.pms.service;

import com.pms.domain.MasterProduct;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Text-binding priority (FEATURE_2608_06 / 12): the channel-override overload layers a cell's own
 * {@code fieldValues} on top of the master/product base. Per key:
 * {@code listing.fieldValues (non-blank) > master.fieldValues (non-blank) > reserved-key product value}.
 * A null / empty cell override leaves the base (03) behavior unchanged.
 */
class ListingTextBindingsTest {

    private Product product() {
        return Product.builder().id(9L).productName("상품명P").name("상품명P").brand("브랜드P").build();
    }

    private MasterProduct master(Map<String, String> fieldValues) {
        return MasterProduct.builder().id(1L).name("마스터").fieldValues(fieldValues).build();
    }

    private ProductListing cell(Map<String, String> fieldValues) {
        return ProductListing.builder().id(100L).platform("COUPANG").name("셀").fieldValues(fieldValues).build();
    }

    @Test
    void listingOverride_winsOverMaster_andReservedKeyFallsBackToMaster() {
        Map<String, String> masterValues = new HashMap<>();
        masterValues.put("brandName", "브랜드M");
        masterValues.put("productName", "상품명M");
        ProductListing cell = cell(Map.of("brandName", "브랜드L")); // override brandName only

        Map<String, String> result = ListingTextBindings.resolve(cell, master(masterValues), product());

        assertThat(result).containsEntry("brandName", "브랜드L");   // listing override wins
        assertThat(result).containsEntry("productName", "상품명M");  // no listing override → master wins
    }

    @Test
    void nullCellOverride_matchesBaseBehavior() {
        Map<String, String> masterValues = Map.of("brandName", "브랜드M");
        ProductListing cell = cell(null);

        Map<String, String> result = ListingTextBindings.resolve(cell, master(masterValues), product());

        assertThat(result).containsEntry("brandName", "브랜드M");            // master value
        assertThat(result).containsEntry("productName", "상품명P");           // reserved key from product
    }

    @Test
    void emptyCellOverride_matchesBaseBehavior() {
        Map<String, String> masterValues = Map.of("brandName", "브랜드M");
        ProductListing cell = cell(Map.of());

        Map<String, String> result = ListingTextBindings.resolve(cell, master(masterValues), product());

        assertThat(result).containsEntry("brandName", "브랜드M");
        assertThat(result).containsEntry("productName", "상품명P");
    }

    @Test
    void blankOverrideValue_skipped_fallsBackToMaster() {
        Map<String, String> masterValues = Map.of("brandName", "브랜드M");
        ProductListing cell = cell(Map.of("brandName", "   ")); // blank → not applied

        Map<String, String> result = ListingTextBindings.resolve(cell, master(masterValues), product());

        assertThat(result).containsEntry("brandName", "브랜드M"); // master value survives
    }
}
