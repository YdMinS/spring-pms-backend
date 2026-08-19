package com.pms.service;

import com.pms.domain.DetailTemplate;
import com.pms.domain.GeneratedContentSource;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.MasterProduct;
import com.pms.domain.MasterProductOption;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.TemplateField;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.response.DetailPreviewResponse;
import com.pms.dto.response.DetailTemplateResponse;
import com.pms.dto.response.GeneratedProductResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.MasterProductOptionRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-generates a channel cell's assets (FEATURE_2608_06 / 3b-2). See {@link ListingAssetService}.
 *
 * <p>Thumbnail input rules (mirroring FEATURE_2608_05 generation): the base photo is
 * {@code master.sourceImageUrl} override ?? the cell's first BOM product image; each text field is
 * {@code master.fieldValues[key] (non-blank) ?? registered-product info ?? template default}. The
 * template is the tenant default. Prices come from {@link PriceCalculator} (per option). The detail HTML
 * is produced by the {@link DetailContentGenerator} seam (stub in 3b-2).</p>
 *
 * <p>⚠️ Reuse-only: options/BOM composition and PurchaseList are untouched — only the option
 * {@code sellingPrice} is written back. {@link ThumbnailRenderer} / {@link ImageStorageService} /
 * {@link ProductImageLoader} are reused as-is.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingAssetServiceImpl implements ListingAssetService {

    private static final String STORAGE_CATEGORY = "thumbnails";

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final MasterProductOptionRepository masterProductOptionRepository;
    private final GeneratedProductDataRepository generatedProductDataRepository;
    private final ChannelTemplateResolver channelTemplateResolver;
    private final ThumbnailRenderer thumbnailRenderer;
    private final ProductImageLoader productImageLoader;
    private final ImageStorageService imageStorageService;
    private final ImageValidator imageValidator;
    private final PriceCalculator priceCalculator;
    private final DetailContentGenerator detailContentGenerator;
    private final com.pms.service.listing.TagMergeService tagMergeService;

    // ---------------------------------------------------------------- endpoints

    @Override
    @Transactional
    public GeneratedProductResponse regenerate(Long listingId) {
        ProductListing cell = requireScopedCell(listingId);
        GeneratedProductData data = regenerateAssets(cell);
        return toResponse(cell, data);
    }

    @Override
    public GeneratedProductResponse getGenerated(Long listingId) {
        ProductListing cell = requireScopedCell(listingId);
        GeneratedProductData data = generatedProductDataRepository.findByProductListingId(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("GeneratedProductData", listingId));
        return toResponse(cell, data);
    }

    @Override
    public DetailPreviewResponse previewDetail(Long listingId) {
        ProductListing cell = requireScopedCell(listingId);
        // Non-persistent AUTO preview from the current master + template (ignores any override — for comparison).
        return DetailPreviewResponse.builder()
                .html(detailContentGenerator.generate(cell))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public DetailTemplateResponse resolveDetailTemplate(Long listingId) {
        ProductListing cell = requireScopedCell(listingId);            // ResourceNotFoundException(404) if absent/cross-tenant
        DetailTemplate t = channelTemplateResolver.resolveDetail(cell); // account-assigned ?? tenant default
        // Inline builder (6 fields) — no shared mapper (a separate util would be over-engineering here).
        return DetailTemplateResponse.builder()
                .id(t.getId()).name(t.getName()).blocks(t.getBlocks())
                .active(t.getActive()).isDefault(t.getIsDefault())
                .build();
    }

    @Override
    @Transactional
    public GeneratedProductResponse overrideDetailHtml(Long listingId, String html) {
        ProductListing cell = requireScopedCell(listingId);
        GeneratedProductData existing = generatedProductDataRepository
                .findByProductListingId(listingId).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        GeneratedProductData toSave = existing != null
                ? existing.toBuilder()
                        .detailHtml(html).source(GeneratedContentSource.MANUAL_OVERRIDE).generatedAt(now).build()
                : GeneratedProductData.builder()
                        .productListing(cell)
                        .detailHtml(html).source(GeneratedContentSource.MANUAL_OVERRIDE)
                        .thumbnailSource(GeneratedContentSource.AUTO).generatedAt(now).build();
        return toResponse(cell, generatedProductDataRepository.save(toSave));
    }

    @Override
    @Transactional
    public GeneratedProductResponse updateFieldValues(Long listingId, Map<String, String> fieldValues) {
        ProductListing cell = requireScopedCell(listingId);
        // Persist the channel override, then regenerate so the thumbnail + option prices reflect it. The
        // detail HTML keeps its Step 2-2 override guard (a MANUAL_OVERRIDE cell preserves its edited HTML).
        // An empty map is stored as-is (override cleared); blank values are naturally skipped at render time.
        ProductListing updated = productListingRepository.save(cell.toBuilder().fieldValues(fieldValues).build());
        GeneratedProductData data = regenerateAssets(updated);
        return toResponse(updated, data);
    }

    @Override
    @Transactional
    public GeneratedProductResponse updateTags(Long listingId, List<String> tags) {
        ProductListing cell = requireScopedCell(listingId);
        // Persist deduped raw channel tags (empty clears). No regenerate/push here — the merged snapshot is
        // recorded at push time. The response reuses the generated view (asset fields null if not generated).
        ProductListing updated = productListingRepository.save(
                cell.toBuilder().tags(tagMergeService.dedup(tags)).build());
        GeneratedProductData data = generatedProductDataRepository.findByProductListingId(listingId).orElse(null);
        return toResponse(updated, data);
    }

    @Override
    @Transactional
    public GeneratedProductResponse clearDetailHtml(Long listingId) {
        ProductListing cell = requireScopedCell(listingId);
        GeneratedProductData existing = generatedProductDataRepository.findByProductListingId(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("GeneratedProductData", listingId));
        // Back to AUTO: re-apply the generator output (thumbnail + prices are untouched here).
        GeneratedProductData toSave = existing.toBuilder()
                .detailHtml(detailContentGenerator.generate(cell))
                .source(GeneratedContentSource.AUTO)
                .generatedAt(LocalDateTime.now())
                .build();
        return toResponse(cell, generatedProductDataRepository.save(toSave));
    }

    @Override
    @Transactional
    public GeneratedProductResponse overrideThumbnail(Long listingId, MultipartFile file) {
        ProductListing cell = requireScopedCell(listingId);
        GeneratedProductData existing = generatedProductDataRepository.findByProductListingId(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("GeneratedProductData", listingId));
        imageValidator.validate(file);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("업로드 파일을 읽을 수 없습니다", e);
        }
        String contentType = StringUtils.hasText(file.getContentType()) ? file.getContentType() : "image/jpeg";
        String url = imageStorageService.uploadBytes(
                bytes, STORAGE_CATEGORY,
                "listing_" + cell.getId() + "_" + System.currentTimeMillis() + ".jpg", contentType);

        String oldUrl = existing.getThumbnailUrl();
        GeneratedProductData saved = generatedProductDataRepository.save(existing.toBuilder()
                .thumbnailUrl(url)
                .thumbnailSource(GeneratedContentSource.MANUAL_OVERRIDE)
                .generatedAt(LocalDateTime.now())
                .build());
        // Best-effort drop the superseded storage object after a successful upsert (detail HTML untouched).
        if (oldUrl != null && !oldUrl.equals(url)) {
            bestEffortDeleteStorage(oldUrl);
        }
        return toResponse(cell, saved);
    }

    @Override
    @Transactional
    public GeneratedProductResponse clearThumbnail(Long listingId) {
        ProductListing cell = requireScopedCell(listingId);
        GeneratedProductData existing = generatedProductDataRepository.findByProductListingId(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("GeneratedProductData", listingId));
        String oldUrl = existing.getThumbnailUrl();
        // Order matters: flip the source to AUTO and persist FIRST, so that regenerateAssets' own
        // findByProductListingId re-reads AUTO and re-renders the thumbnail (bypassing the override guard).
        // The AUTO render lives inside the regenerateAssets seam, so we cannot inline it like clearDetailHtml.
        generatedProductDataRepository.save(existing.toBuilder()
                .thumbnailSource(GeneratedContentSource.AUTO).build());
        GeneratedProductData data = regenerateAssets(cell);
        // Best-effort drop the superseded override image after the AUTO re-render produced a new url.
        if (oldUrl != null && !oldUrl.equals(data.getThumbnailUrl())) {
            bestEffortDeleteStorage(oldUrl);
        }
        return toResponse(cell, data);
    }

    // ---------------------------------------------------------------- seam

    @Override
    @Transactional
    public GeneratedProductData regenerateAssets(ProductListing cell) {
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(cell.getId());
        Product firstProduct = firstBomProduct(options);

        GeneratedProductData existing = generatedProductDataRepository
                .findByProductListingId(cell.getId()).orElse(null);

        // 1. Thumbnail — override guard (25): a MANUAL_OVERRIDE cell keeps its uploaded thumbnail (nothing
        //    is re-rendered or re-uploaded); otherwise (new / AUTO) the renderer produces it. This mirrors
        //    the detail-HTML guard below and is fully independent of it.
        boolean thumbOverride = existing != null
                && existing.getThumbnailSource() == GeneratedContentSource.MANUAL_OVERRIDE;
        String thumbnailUrl;
        GeneratedContentSource thumbSource;
        Long templateId = existing != null ? existing.getTemplateId() : null;
        if (thumbOverride) {
            thumbnailUrl = existing.getThumbnailUrl();               // preserved, not re-rendered/uploaded
            thumbSource = GeneratedContentSource.MANUAL_OVERRIDE;
        } else {
            byte[] baseImage = resolveBaseImage(cell.getMasterProduct(), firstProduct);
            // Channel template override (21): account's assigned thumbnail template ?? tenant default.
            ThumbnailTemplate template = channelTemplateResolver.resolveThumbnail(cell);
            Map<String, String> textBindings = buildTextBindings(template, cell);
            byte[] jpeg = thumbnailRenderer.render(template, textBindings, Map.of("productImage", baseImage));
            thumbnailUrl = imageStorageService.uploadBytes(
                    jpeg, STORAGE_CATEGORY,
                    "listing_" + cell.getId() + "_" + System.currentTimeMillis() + ".jpg", "image/jpeg");
            thumbSource = GeneratedContentSource.AUTO;
            templateId = template.getId();
        }

        // 2. Detail HTML — override guard: a MANUAL_OVERRIDE cell keeps its edited detailHtml (thumbnail
        //    and option prices are still regenerated); otherwise (new / AUTO) the generator produces it.
        boolean override = existing != null && existing.getSource() == GeneratedContentSource.MANUAL_OVERRIDE;
        String detailHtml = override ? existing.getDetailHtml() : detailContentGenerator.generate(cell);
        GeneratedContentSource source = override ? GeneratedContentSource.MANUAL_OVERRIDE : GeneratedContentSource.AUTO;

        // 3. Per-option selling price (margin reverse-calc); write back only sellingPrice. Match each listing
        //    option to its master option by optionName (one query, outside the loop — no N+1); an unmatched
        //    option means "no override" → the price engine falls back to the master defaults.
        Map<String, MasterProductOption> masterOptionsByName = cell.getMasterProduct() == null
                ? Map.of()
                : masterProductOptionRepository.findByMasterProductId(cell.getMasterProduct().getId()).stream()
                        .collect(Collectors.toMap(MasterProductOption::getName, Function.identity(), (a, b) -> a));
        for (ProductListingOption option : options) {
            MasterProductOption mo = masterOptionsByName.get(option.getOptionName());
            BigDecimal price = priceCalculator.calculatePrice(cell, mo, optionCostSum(option));
            productListingOptionRepository.save(option.toBuilder().sellingPrice(price).build());
        }

        // 4. Upsert the assets row.
        return upsert(cell, existing, thumbnailUrl, thumbSource, detailHtml, templateId, source);
    }

    // ---------------------------------------------------------------- helpers

    private ProductListing requireScopedCell(Long id) {
        return productListingRepository.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", id));
    }

    /**
     * Best-effort storage delete — never rolls back the transaction. {@code deleteImage} is graceful and
     * branches on path vs URL, so passing the stored thumbnail value is compatible with Local and S3.
     */
    private void bestEffortDeleteStorage(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            imageStorageService.deleteImage(url);
        } catch (Exception e) {
            log.warn("Best-effort listing thumbnail storage delete failed: {}", url, e);
        }
    }

    /** Base thumbnail photo bytes: master override url ?? first BOM product image (400 if neither). */
    private byte[] resolveBaseImage(MasterProduct master, Product firstProduct) {
        if (master != null && StringUtils.hasText(master.getSourceImageUrl())) {
            return productImageLoader.loadUrl(master.getSourceImageUrl());
        }
        if (firstProduct == null) {
            throw new IllegalArgumentException("셀에 등록상품이 없습니다");
        }
        return productImageLoader.load(firstProduct);
    }

    /** First option's first BOM product (base photo + product-info source), or null if none. */
    private Product firstBomProduct(List<ProductListingOption> options) {
        if (options.isEmpty()) {
            return null;
        }
        List<ProductListingProduct> bom = productListingProductRepository
                .findByProductListingOptionId(options.get(0).getId());
        return bom.isEmpty() ? null : bom.get(0).getProduct();
    }

    /**
     * Thumbnail text bindings: over the template's fields, pure binding ?? template default; blank → key
     * omitted (element skipped). The pure binding (fieldValues ?? first-BOM-product info, no default) is
     * the {@link #resolveTextBindings(ProductListing)} helper shared with the detail generator.
     */
    private Map<String, String> buildTextBindings(ThumbnailTemplate template, ProductListing cell) {
        Map<String, String> bindings = new HashMap<>();
        if (template.getFields() == null) {
            return bindings;
        }
        Map<String, String> pure = resolveTextBindings(cell);
        for (TemplateField field : template.getFields()) {
            String value = pure.get(field.getKey());
            if (!StringUtils.hasText(value)) {
                value = field.getDefaultValue();
            }
            if (StringUtils.hasText(value)) {
                bindings.put(field.getKey(), value);
            }
        }
        return bindings;
    }

    /**
     * Pure text bindings shared with the detail generator: master.fieldValues (non-blank) plus the
     * reserved keys (brandName/productName) derived from the cell's first BOM product when absent. No
     * defaultValue fallback here (that is each renderer's own concern). Extracting this leaves the
     * thumbnail bindings unchanged — {@code ListingAssetServiceTest} proves it.
     */
    private Map<String, String> resolveTextBindings(ProductListing cell) {
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(cell.getId());
        // Channel override (12): listing.fieldValues (non-blank) > master.fieldValues > reserved-key product value.
        return ListingTextBindings.resolve(cell, cell.getMasterProduct(), firstBomProduct(options));
    }

    /** Σ(product.price × quantity) over an option's BOM (null price treated as 0). */
    private BigDecimal optionCostSum(ProductListingOption option) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ProductListingProduct bp : productListingProductRepository.findByProductListingOptionId(option.getId())) {
            BigDecimal price = bp.getProduct().getPrice();
            if (price != null) {
                sum = sum.add(price.multiply(BigDecimal.valueOf(bp.getQuantity())));
            }
        }
        return sum;
    }

    /** Rebuild the pre-fetched existing assets row (same id) or insert a new one. */
    private GeneratedProductData upsert(ProductListing cell, GeneratedProductData existing,
                                       String thumbnailUrl, GeneratedContentSource thumbnailSource,
                                       String detailHtml, Long templateId, GeneratedContentSource source) {
        LocalDateTime now = LocalDateTime.now();
        GeneratedProductData toSave = existing != null
                ? existing.toBuilder()
                        .thumbnailUrl(thumbnailUrl).thumbnailSource(thumbnailSource).detailHtml(detailHtml)
                        .templateId(templateId).generatedAt(now).source(source).build()
                : GeneratedProductData.builder()
                        .productListing(cell)
                        .thumbnailUrl(thumbnailUrl).thumbnailSource(thumbnailSource).detailHtml(detailHtml)
                        .templateId(templateId).generatedAt(now).source(source).build();
        return generatedProductDataRepository.save(toSave);
    }

    /** Cell view; {@code data} may be null (e.g. the tags endpoint on a not-yet-generated cell → asset fields null). */
    private GeneratedProductResponse toResponse(ProductListing cell, GeneratedProductData data) {
        List<GeneratedProductResponse.OptionPrice> optionPrices = productListingOptionRepository
                .findByProductListingId(cell.getId()).stream()
                .map(o -> GeneratedProductResponse.OptionPrice.builder()
                        .optionId(o.getId())
                        .sellingPrice(o.getSellingPrice())
                        .build())
                .toList();
        return GeneratedProductResponse.builder()
                .productListingId(cell.getId())
                .thumbnailUrl(data != null ? data.getThumbnailUrl() : null)
                .detailHtml(data != null ? data.getDetailHtml() : null)
                .source(data != null ? data.getSource() : null)
                .thumbnailSource(data != null ? data.getThumbnailSource() : null)
                .fieldValues(cell.getFieldValues() != null ? cell.getFieldValues() : Map.of())
                .tags(cell.getTags() != null ? cell.getTags() : List.of())
                .optionPrices(optionPrices)
                .build();
    }
}
