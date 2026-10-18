package com.pms.service;

import com.pms.dto.request.PurchaseSettingRequest;
import com.pms.dto.response.PurchaseSettingResponse;

/**
 * Tenant purchase settings — the stock link switch (FEATURE_2610_09 / D65 · D69 · D72).
 *
 * <p>No row = off (D69). Reading is open to every signed-in user (menus hide 「재고 조회」 by it, D71 ②);
 * writing is ADMIN only.</p>
 */
public interface PurchaseSettingService {

    PurchaseSettingResponse get();

    PurchaseSettingResponse update(PurchaseSettingRequest request);

    /**
     * The value {@code PurchaseListServiceImpl.addPurchase} reads before it writes stock (D65 · D84), and
     * {@code StockLedgerServiceImpl} reads to decide whether a PURCHASE check-in needs a purchase record
     * (D71 round 12).
     */
    boolean isStockLinked();
}
