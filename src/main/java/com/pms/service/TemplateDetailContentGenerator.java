package com.pms.service;

import com.pms.domain.DetailTemplate;
import com.pms.domain.ImageOp;
import com.pms.domain.MasterImageZoneAssignment;
import com.pms.domain.MasterProduct;
import com.pms.domain.ProcessingPreset;
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
 * <p>⚠️ The generator only fills {@code textBindings}; the {@code defaultValue} fallback is the renderer's
 * single responsibility (do NOT assemble defaults here).</p>
 *
 * <p>⚠️ Not pure anymore (FEATURE_2608_08): when the channel's {@link DetailTemplate} references an image
 * {@link ProcessingPreset}, each zone image is loaded, composited through {@link ImageProcessor}
 * (watermarks/badges burned per channel), re-uploaded, and its URL swapped before rendering. With no
 * preset (or empty ops) the URLs pass through verbatim (no I/O). The processor/storage/loader are injected,
 * so this stays a Mockito unit test. LAZY {@code template.getImageProcessingPreset()} /
 * {@code preset.getOperations()} are read inside the existing {@code @Transactional(readOnly)} boundary.</p>
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
    private final DetailFontResolver detailFontResolver;
    private final ImageProcessor imageProcessor;
    private final ImageStorageService imageStorageService;
    private final ProductImageLoader productImageLoader;

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
        zoneImageUrls = applyImageProcessing(template, master.getId(), zoneImageUrls);
        // Fonts are a DB lookup (FontAsset ids in textStyle) → resolved here, keeping the renderer pure.
        Map<String, DetailFont> fonts = detailFontResolver.resolve(template.getBlocks());
        return detailHtmlRenderer.render(template, textBindings, zoneImageUrls, fonts);
    }

    /**
     * Burn the channel template's image {@link ProcessingPreset} (watermark/badge overlays) into each zone
     * image, returning URLs of the freshly uploaded composites. No preset / empty ops → the original URLs
     * are returned verbatim (no I/O). Composites are per-cell files (channel-specific), so the filename is
     * unique per {master, preset, zone, index}. Re-generation re-composites (previous files orphaned —
     * best-effort cleanup is out of scope).
     */
    private Map<String, List<String>> applyImageProcessing(DetailTemplate template, Long masterId,
                                                           Map<String, List<String>> zoneImageUrls) {
        ProcessingPreset preset = template.getImageProcessingPreset();
        List<ImageOp> ops = preset == null ? null : preset.getOperations();
        if (ops == null || ops.isEmpty()) {
            return zoneImageUrls; // no compositing — pass through
        }
        Map<String, List<String>> processed = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> zone : zoneImageUrls.entrySet()) {
            List<String> outUrls = new ArrayList<>();
            List<String> urls = zone.getValue();
            for (int i = 0; i < urls.size(); i++) {
                byte[] baseBytes = productImageLoader.loadUrl(urls.get(i));
                byte[] out = imageProcessor.process(baseBytes, ops);
                String filename = masterId + "_" + preset.getId() + "_" + zone.getKey() + "_" + i + ".jpg";
                outUrls.add(imageStorageService.uploadBytes(out, "master-detail", filename, "image/jpeg"));
            }
            processed.put(zone.getKey(), outUrls);
        }
        return processed;
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
