package com.pms.service.category;

import com.pms.dto.response.LotteonCategoryImportResult;
import com.pms.dto.response.LotteonFeeImportResult;

/**
 * Seeds the LotteON standard category list and its commissions (FEATURE_2610_24 / D9 · D10 · D46 · D48).
 *
 * <p>Both write only {@code platform_category} rows of platform LOTTEON — no standard category and no mapping is
 * created (people connect them one by one, D9). The tree is read with one LotteON sales channel's key, so it runs on
 * the operating server only (D10). Order: {@link #importTree} first, then {@link #importFees} (D46).</p>
 */
public interface LotteonCategoryImportService {

    /** D9 · D10: reads every page of the standard category lookup (205) and upserts the tree. */
    LotteonCategoryImportResult importTree(Long accountId);

    /** D46 · D48: reads the commission CSV and overwrites the commission of every leaf it reaches. */
    LotteonFeeImportResult importFees(byte[] csv);
}
