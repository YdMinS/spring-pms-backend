package com.pms.service.category;

import com.pms.dto.response.ElevenstCategoryImportResult;
import com.pms.dto.response.ElevenstFeeImportResult;

/**
 * Seeds the 11st marketplace category list and its commissions (FEATURE_2610_10).
 *
 * <p>Both write only {@code platform_category} rows of platform ELEVENST — no standard category and no mapping
 * is created (D7). Order: {@link #importTree()} first, then {@link #importFees(byte[])} (D22 · D31).</p>
 */
public interface ElevenstCategoryImportService {

    /** Downloads the public 11st tree and upserts it (D21 ① ②). */
    ElevenstCategoryImportResult importTree();

    /** Reads the saved fee notice page and writes leaf commissions (D22 · D25 ~ D31). */
    ElevenstFeeImportResult importFees(byte[] html);
}
