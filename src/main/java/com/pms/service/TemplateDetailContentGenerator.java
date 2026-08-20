package com.pms.service;

import com.pms.domain.DetailTemplate;
import com.pms.domain.MasterImageZoneAssignment;
import com.pms.domain.MasterProduct;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.repository.MasterImageZoneAssignmentRepository;
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

    private final ChannelTemplateResolver channelTemplateResolver;
    private final ProductImageUrlResolver productImageUrlResolver;
    private final MasterImageZoneAssignmentRepository masterImageZoneAssignmentRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final DetailHtmlRenderer detailHtmlRenderer;

    @Override
    public String generate(ProductListing cell) {
        MasterProduct master = cell.getMasterProduct();
        if (master == null) {
            return ""; // backfill-transition guard (nullable master), matching the stub's leniency
        }
        // Channel template override (21): account's assigned detail template ?? tenant default
        // (resolver throws if neither exists, so the old null guard is removed).
        DetailTemplate template = channelTemplateResolver.resolveDetail(cell);
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

    /**
     * Zone id → image URLs in sortOrder, from the field mappings (37). The cover-photo key
     * ({@code __source__}) is excluded — it is not a detail zone.
     */
    private Map<String, List<String>> resolveZoneImageUrls(Long masterId) {
        Map<String, List<String>> zones = new LinkedHashMap<>();
        for (MasterImageZoneAssignment a : masterImageZoneAssignmentRepository
                .findByImage_MasterProductIdOrderByZoneIdAscSortOrderAsc(masterId)) {
            if (MasterImageZoneAssignment.SOURCE_ZONE.equals(a.getZoneId())) {
                continue;
            }
            // Effective URL: a reference entry resolves to the live product image URL (40, same priority
            // as the batch COALESCE finder) — no new imageUrl access point.
            zones.computeIfAbsent(a.getZoneId(), k -> new ArrayList<>())
                    .add(productImageUrlResolver.resolve(a.getImage()));
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
