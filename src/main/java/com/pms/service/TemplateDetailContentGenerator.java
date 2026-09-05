package com.pms.service;

import com.pms.domain.DetailBlock;
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
import com.pms.repository.ProcessingPresetRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
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
 * <p>⚠️ Not pure anymore (FEATURE_2608_08): when a zone's block resolves to an image {@link ProcessingPreset},
 * its images are loaded, composited through {@link ImageProcessor} (watermarks/badges burned per channel),
 * re-uploaded, and their URLs swapped before rendering. The preset is per block
 * ({@code DetailBlock.processingPresetId}) with the template-level {@code imageProcessingPreset} as the
 * fallback (03), so one template can watermark two zones differently. Composites of a block-specified
 * preset are keyed {@code bind + "#" + presetId}; an inherited preset overwrites the plain {@code bind}
 * entry, which keeps the pre-03 behaviour byte-identical. With no preset (or empty ops) the URLs pass
 * through verbatim (no I/O). The processor/storage/loader are injected, so this stays a Mockito unit test.
 * LAZY {@code template.getImageProcessingPreset()} / {@code preset.getOperations()} are read inside the
 * existing {@code @Transactional(readOnly)} boundary.</p>
 */
@Service
@Slf4j
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
    private final ProcessingPresetRepository processingPresetRepository;

    @Override
    public String generate(ProductListing cell) {
        if (cell.getMasterProduct() == null) {
            return ""; // backfill-transition guard (nullable master) — bail out before resolving a template
        }
        // Channel template override (21 + 2609_20/D2): cell ?? account ?? tenant default
        // (resolver throws if none exists, so the old null guard is removed).
        return generate(cell, channelTemplateResolver.resolveDetail(cell));
    }

    /**
     * The render itself (2609_20/D4) — the only place the HTML is composed, so the resolved and the
     * injected-template paths cannot drift apart.
     */
    @Override
    public String generate(ProductListing cell, DetailTemplate template) {
        MasterProduct master = cell.getMasterProduct();
        if (master == null) {
            return ""; // backfill-transition guard (nullable master), matching the stub's leniency
        }
        Map<String, String> textBindings = resolveTextBindings(cell);
        Map<String, List<String>> zoneImageUrls = resolveZoneImageUrls(master.getId());
        zoneImageUrls = applyImageProcessing(template, master.getId(), zoneImageUrls);
        // Fonts are a DB lookup (FontAsset ids in textStyle) → resolved here, keeping the renderer pure.
        Map<String, DetailFont> fonts = detailFontResolver.resolve(template.getBlocks());
        return detailHtmlRenderer.render(template, textBindings, zoneImageUrls, fonts);
    }

    /**
     * Burn each image zone's resolved {@link ProcessingPreset} (watermark/badge overlays) into its images,
     * returning URLs of the freshly uploaded composites. The preset of an {@code imageZone} block is its own
     * {@code processingPresetId}, falling back to the template's {@code imageProcessingPreset}; neither set
     * (or empty ops) leaves the zone's original URLs in place (no I/O).
     *
     * <p>Output keys are decided per block — a block that names its own preset gets {@code bind + "#" +
     * presetId}, an inherited one overwrites the plain {@code bind} slot — while compositing is cached per
     * {@code (zone, preset)} combination, so two blocks sharing a preset upload once and are served under
     * both keys. Composites are per-cell files (channel-specific), so the filename is unique per
     * {master, preset, zone, index}. Re-generation re-composites (previous files orphaned — best-effort
     * cleanup is out of scope).</p>
     */
    private Map<String, List<String>> applyImageProcessing(DetailTemplate template, Long masterId,
                                                           Map<String, List<String>> zoneImageUrls) {
        List<DetailBlock> blocks = template.getBlocks();
        if (blocks == null || blocks.isEmpty()) {
            return zoneImageUrls;
        }
        ProcessingPreset templatePreset = template.getImageProcessingPreset();
        Long templatePresetId = templatePreset == null ? null : templatePreset.getId();

        Map<String, List<String>> result = new LinkedHashMap<>(zoneImageUrls);
        Map<String, List<String>> cacheByCombo = new HashMap<>();   // "zone#preset" → composites (no duplicate I/O)
        Map<Long, ProcessingPreset> presetCache = new HashMap<>();
        if (templatePresetId != null) {
            presetCache.put(templatePresetId, templatePreset);      // already loaded — never re-fetch
        }

        for (DetailBlock block : blocks) {
            if (block == null || !"imageZone".equals(block.getType()) || block.getBind() == null) {
                continue;
            }
            Long explicitId = block.getProcessingPresetId();        // null = inherit the template preset
            Long presetId = explicitId != null ? explicitId : templatePresetId;
            if (presetId == null) {
                continue;                                           // no preset at all → originals stay
            }
            String bind = block.getBind();
            List<String> urls = zoneImageUrls.get(bind);
            if (urls == null || urls.isEmpty()) {
                continue;
            }
            // The key depends on the block, the composite only on the (zone, preset) pair.
            String outKey = explicitId != null ? bind + "#" + presetId : bind;
            String combo = bind + "#" + presetId;
            List<String> cached = cacheByCombo.get(combo);
            if (cached != null) {
                result.put(outKey, cached);
                continue;
            }
            if (!presetCache.containsKey(presetId)) {
                // computeIfAbsent would not cache a null, re-querying a missing id for every block.
                presetCache.put(presetId, processingPresetRepository.findScopedById(presetId).orElse(null));
            }
            ProcessingPreset preset = presetCache.get(presetId);
            if (preset == null) {
                log.warn("Detail image preset {} not found for zone {} — leaving the original images", presetId, bind);
                continue;                                           // skip this combo, never fail the whole page
            }
            List<ImageOp> ops = preset.getOperations();
            if (ops == null || ops.isEmpty()) {
                continue;                                           // nothing to burn → originals stay
            }
            List<String> outUrls = new ArrayList<>();
            for (int i = 0; i < urls.size(); i++) {
                byte[] baseBytes = productImageLoader.loadUrl(urls.get(i));
                byte[] out = imageProcessor.process(baseBytes, ops);
                String filename = masterId + "_" + presetId + "_" + bind + "_" + i + ".jpg";
                outUrls.add(imageStorageService.uploadBytes(out, "master-detail", filename, "image/jpeg"));
            }
            cacheByCombo.put(combo, outUrls);
            result.put(outKey, outUrls);
        }
        return result;
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
