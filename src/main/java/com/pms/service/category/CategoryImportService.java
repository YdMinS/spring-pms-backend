package com.pms.service.category;

import com.pms.dto.response.CategoryImportResult;

import java.io.InputStream;

/**
 * Seeds the marketplace category model from a Coupang bulk-category xlsx (FEATURE_2608_06 / 53).
 *
 * <p>Idempotently builds the {@link com.pms.domain.PlatformCategory} (Coupang) tree + commission, the oclyx
 * {@link com.pms.domain.Category} mirror tree (option A), and the leaf {@link com.pms.domain.CategoryMapping}
 * (oclyx leaf ↔ PlatformCategory). Re-import reflects only new leaves and preserves existing oclyx curation.</p>
 */
public interface CategoryImportService {

    CategoryImportResult importCoupang(InputStream xlsx);
}
