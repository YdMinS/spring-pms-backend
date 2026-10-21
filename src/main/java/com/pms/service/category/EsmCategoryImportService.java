package com.pms.service.category;

import com.pms.dto.response.EsmCategoryImportResult;
import com.pms.dto.response.EsmFeeImportResult;

/**
 * Seeds the ESM (Gmarket · Auction) category list and its two commissions (FEATURE_2610_26 / D11 · D12 · D14).
 *
 * <p>Both write only {@code platform_category} rows of platform ESM — no standard category and no mapping is created
 * (people connect them one by one, D12). Both read ESM with one ESM sales channel's key, so both run on the operating
 * server only (D10). Order: {@link #importTree} first, then {@link #importFees} (D63).</p>
 */
public interface EsmCategoryImportService {

    /** D12: walks the ESM category tree from the top level down to every leaf and upserts it. */
    EsmCategoryImportResult importTree(Long accountId);

    /** D14 · D62 · D63 · D64 · D67: reads the fee table file and writes both commissions of every leaf it reaches. */
    EsmFeeImportResult importFees(Long accountId, byte[] xlsx);
}
