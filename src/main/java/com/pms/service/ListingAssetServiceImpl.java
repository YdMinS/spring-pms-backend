package com.pms.service;

import com.pms.domain.GeneratedContentSource;
import com.pms.domain.GeneratedProductData;
import com.pms.domain.MasterProduct;
import com.pms.domain.Product;
import com.pms.domain.ProductListing;
import com.pms.domain.ProductListingOption;
import com.pms.domain.ProductListingProduct;
import com.pms.domain.TemplateField;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.response.DetailPreviewResponse;
import com.pms.dto.response.GeneratedProductResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.GeneratedProductDataRepository;
import com.pms.repository.ProductListingOptionRepository;
import com.pms.repository.ProductListingProductRepository;
import com.pms.repository.ProductListingRepository;
import com.pms.repository.ThumbnailTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ListingAssetServiceImpl implements ListingAssetService {

    private static final String STORAGE_CATEGORY = "thumbnails";

    private final ProductListingRepository productListingRepository;
    private final ProductListingOptionRepository productListingOptionRepository;
    private final ProductListingProductRepository productListingProductRepository;
    private final GeneratedProductDataRepository generatedProductDataRepository;
    private final ThumbnailTemplateRepository thumbnailTemplateRepository;
    private final ThumbnailRenderer thumbnailRenderer;
    private final ProductImageLoader productImageLoader;
    private final ImageStorageService imageStorageService;
    private final PriceCalculator priceCalculator;
    private final DetailContentGenerator detailContentGenerator;

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
                        .detailHtml(html).source(GeneratedContentSource.MANUAL_OVERRIDE).generatedAt(now).build();
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

    // ---------------------------------------------------------------- seam

    @Override
    @Transactional
    public GeneratedProductData regenerateAssets(ProductListing cell) {
        List<ProductListingOption> options = productListingOptionRepository.findByProductListingId(cell.getId());
        Product firstProduct = firstBomProduct(options);

        // 1. Thumbnail: master override photo, else the cell's first BOM product photo.
        byte[] baseImage = resolveBaseImage(cell.getMasterProduct(), firstProduct);
        ThumbnailTemplate template = thumbnailTemplateRepository.findByIsDefaultTrueAndActiveTrue()
                .orElseThrow(() -> new IllegalArgumentException("기본 템플릿이 없습니다"));
        Map<String, String> textBindings = buildTextBindings(template, cell);
        byte[] jpeg = thumbnailRenderer.render(template, textBindings, Map.of("productImage", baseImage));
        String thumbnailUrl = imageStorageService.uploadBytes(
                jpeg, STORAGE_CATEGORY,
                "listing_" + cell.getId() + "_" + System.currentTimeMillis() + ".jpg", "image/jpeg");

        // 2. Detail HTML — override guard: a MANUAL_OVERRIDE cell keeps its edited detailHtml (thumbnail
        //    and option prices are still regenerated); otherwise (new / AUTO) the generator produces it.
        GeneratedProductData existing = generatedProductDataRepository
                .findByProductListingId(cell.getId()).orElse(null);
        boolean override = existing != null && existing.getSource() == GeneratedContentSource.MANUAL_OVERRIDE;
        String detailHtml = override ? existing.getDetailHtml() : detailContentGenerator.generate(cell);
        GeneratedContentSource source = override ? GeneratedContentSource.MANUAL_OVERRIDE : GeneratedContentSource.AUTO;

        // 3. Per-option selling price (margin reverse-calc); write back only sellingPrice.
        for (ProductListingOption option : options) {
            BigDecimal price = priceCalculator.calculatePrice(cell, optionCostSum(option));
            productListingOptionRepository.save(option.toBuilder().sellingPrice(price).build());
        }

        // 4. Upsert the assets row.
        return upsert(cell, existing, thumbnailUrl, detailHtml, template.getId(), source);
    }

    // ---------------------------------------------------------------- helpers

    private ProductListing requireScopedCell(Long id) {
        return productListingRepository.findScopedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductListing", id));
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
                                       String thumbnailUrl, String detailHtml, Long templateId,
                                       GeneratedContentSource source) {
        LocalDateTime now = LocalDateTime.now();
        GeneratedProductData toSave = existing != null
                ? existing.toBuilder()
                        .thumbnailUrl(thumbnailUrl).detailHtml(detailHtml)
                        .templateId(templateId).generatedAt(now).source(source).build()
                : GeneratedProductData.builder()
                        .productListing(cell)
                        .thumbnailUrl(thumbnailUrl).detailHtml(detailHtml)
                        .templateId(templateId).generatedAt(now).source(source).build();
        return generatedProductDataRepository.save(toSave);
    }

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
                .thumbnailUrl(data.getThumbnailUrl())
                .detailHtml(data.getDetailHtml())
                .source(data.getSource())
                .fieldValues(cell.getFieldValues() != null ? cell.getFieldValues() : Map.of())
                .optionPrices(optionPrices)
                .build();
    }
}
