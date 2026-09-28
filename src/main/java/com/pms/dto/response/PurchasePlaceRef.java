package com.pms.dto.response;

/**
 * A purchase place as carried on a product response (FEATURE_2609_76 / D3): the id the product stores plus
 * the place's CURRENT name — a rename shows up here with no change to the product.
 */
public record PurchasePlaceRef(Long id, String name) {
}
