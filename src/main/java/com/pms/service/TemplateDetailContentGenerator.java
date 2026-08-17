package com.pms.service;

import com.pms.domain.DetailTemplate;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductImage;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.repository.DetailTemplateRepository;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real {@link DetailContentGenerator} (FEATURE_2608_06 / Step 2-2) — replaces the 3b-2 stub bean without
 * changing the wiring. Composes a cell's detail HTML from the master's text {@code fieldValues}, its
 * zone input images, and the tenant's default {@link DetailTemplate}, delegating to {@link DetailHtmlRenderer}.
 *
 * <p>Pure composition (no I/O — image URLs are passed through verbatim), so it is Mockito-unit-testable.
 * ⚠️ The generator only fills {@code textBindings}; the {@code defaultValue} fallback is the renderer's
 * single responsibility (do NOT assemble defaults here).</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TemplateDetailContentGenerator implements DetailContentGenerator {

    private final DetailTemplateRepository detailTemplateRepository;
    private final MasterProductImageRepository masterProductImageRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final DetailHtmlRenderer detailHtmlRenderer;

    @Override
    public String generate(ProductListing cell) {
        MasterProduct master = cell.getMasterProduct();
        if (master == null) {
            return ""; // backfill-transition guard (nullable master), matching the stub's leniency
        }
        DetailTemplate template = detailTemplateRepository.findByIsDefaultTrueAndActiveTrue().orElse(null);
        if (template == null) {
            return ""; // the seeder guarantees a default, but be defensive
        }
        Map<String, String> textBindings = resolveTextBindings(cell);
        Map<String, List<String>> zoneImageUrls = resolveZoneImageUrls(master.getId());
        return detailHtmlRenderer.render(template, textBindings, zoneImageUrls);
    }

    /**
     * Same fieldValues ?? first-BOM-product assembly as the thumbnail path (no defaultValue fallback), with
     * the cell's channel override layered on top per key (FEATURE_2608_06 / 12).
     */
    private Map<String, String> resolveTextBindings(ProductListing cell) {
        return ListingTextBindings.resolve(cell, cell.getMasterProduct(), firstBomProduct(cell));
    }

    /** Zone id → image URLs in sortOrder (ordering guaranteed by the query; this only groups). */
    private Map<String, List<String>> resolveZoneImageUrls(Long masterId) {
        Map<String, List<String>> zones = new LinkedHashMap<>();
        for (MasterProductImage image : masterProductImageRepository
                .findByMasterProductIdOrderByZoneIdAscSortOrderAsc(masterId)) {
            zones.computeIfAbsent(image.getZoneId(), k -> new ArrayList<>()).add(image.getImageUrl());
        }
        return zones;
    }

    /** The cell's first option's first BOM product (reserved-key derivation source), or null if none. */
    private Product firstBomProduct(ProductListing cell) {
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(cell.getId());
        if (options.isEmpty()) {
            return null;
        }
        List<ProductListingProduct> bom = productListingProductRepository
                .findByProductListingOptionId(options.get(0).getId());
        return bom.isEmpty() ? null : bom.get(0).getProduct();
    }
}
