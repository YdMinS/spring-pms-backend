package com.pms.service.listing;

/**
 * One listing of the 02:05 market price batch (FEATURE_2610_27 / D13 ② · D22~D27). Called by
 * {@link MarketPriceSyncScheduler} through the Spring proxy so every listing is its own transaction (D24).
 */
public interface MarketPriceSyncService {

    /**
     * Read the market prices of one listing and store them ({@link MarketPriceApplier}). The listing status is not
     * changed. A failed read throws and rolls this listing back — its previous values stay (D24).
     *
     * @param listingId a cell of the current tenant that is on a market
     * @return true when at least one option's system price changed (D34)
     */
    boolean syncOne(Long listingId);
}
