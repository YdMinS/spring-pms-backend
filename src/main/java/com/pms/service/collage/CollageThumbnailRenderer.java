package com.pms.service.collage;

import com.pms.domain.BackgroundMode;
import com.pms.domain.CollageCell;
import com.pms.domain.CollageLayout;
import com.pms.domain.ImageOp;
import com.pms.domain.Product;
import com.pms.domain.ProductImage;
import com.pms.domain.TemplateElement;
import com.pms.domain.ThumbnailTemplate;
import com.pms.repository.ProductImageRepository;
import com.pms.service.ImageDecodeSupport;
import com.pms.service.ImageProcessor;
import com.pms.service.ProductImageLoader;
import com.pms.service.ThumbnailPresetResolver;
import com.pms.service.ThumbnailRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collage thumbnail JPEG (FEATURE_2610_21 D5 · D10 · D18 · D39 · D48 · D49 · D50) — shared by generation
 * ({@code ListingAssetServiceImpl.regenerateAssets}) and the editor preview, so both take the same path (D35).
 *
 * <ol>
 *   <li>Cell photos = the chosen {@code productImageId} when it is still a photo of that cell's product, otherwise the
 *       product's cover {@code Product.imageUrl} (FEATURE_2610_32 D15 · D17 — a deleted or foreign choice falls back
 *       silently). A cell with neither is left out (its cell stays empty, D18); none at all → 400 「셀에 등록상품이
 *       없습니다」.</li>
 *   <li>Size (D39): {@code useTemplate} → the template's first product-photo slot w×h; otherwise — or when the
 *       template has no such slot — the template canvas w×h.</li>
 *   <li>The template's product-photo preset runs once on the finished collage (D5 · D49), flattened onto white
 *       first (2609_81 D21).</li>
 *   <li>{@code useTemplate} → {@link ThumbnailRenderer#render} with the template and the collage bound to
 *       {@code productImage}; otherwise → the same renderer with a white canvas-sized template whose only element is
 *       a full-canvas {@code productImage} slot, so the collage is the whole thumbnail.</li>
 * </ol>
 *
 * <p>Usage: {@code byte[] jpeg = collageThumbnailRenderer.render(template, textBindings, layout, products);}</p>
 *
 * <p>❌ Do not encode the thumbnail JPEG here — both branches end in {@link ThumbnailRenderer#render}, the same call
 * the single-photo thumbnail makes. File: {@code service/collage/CollageThumbnailRenderer.java}.</p>
 */
@Component
@RequiredArgsConstructor
public class CollageThumbnailRenderer {

    private static final String PRODUCT_IMAGE_BIND = "productImage";

    private final CollageRenderer collageRenderer;
    private final ProductImageLoader productImageLoader;
    private final ImageDecodeSupport imageDecodeSupport;
    private final ThumbnailPresetResolver thumbnailPresetResolver;
    private final ImageProcessor imageProcessor;
    private final ThumbnailRenderer thumbnailRenderer;
    private final ProductImageRepository productImageRepository;

    /**
     * @param template     the listing's thumbnail template (or the tenant default for a preview without a listing)
     * @param textBindings text bindings for the template (ignored when {@code useTemplate} is false)
     * @param layout       a validated / adjusted collage
     * @param products     the master's components, ascending id
     * @return thumbnail JPEG bytes
     */
    public byte[] render(ThumbnailTemplate template, Map<String, String> textBindings,
                         CollageLayout layout, List<Product> products) {
        Map<Long, BufferedImage> photos = new HashMap<>();
        Map<Long, ProductImage> chosen = chosenPhotos(layout);
        for (Product product : products) {
            CollageCell cell = layout.cells().stream()
                    .filter(c -> product.getId().equals(c.productId()))
                    .findFirst()
                    .orElse(null);
            ProductImage pick = cell == null || cell.productImageId() == null ? null : chosen.get(cell.productImageId());
            if (pick != null && pick.getProduct() != null && product.getId().equals(pick.getProduct().getId())) {
                photos.put(product.getId(),
                        imageDecodeSupport.decode(productImageLoader.loadUrl(pick.getImageUrl()), "collage photo"));
            } else if (StringUtils.hasText(product.getImageUrl())) {
                photos.put(product.getId(),
                        imageDecodeSupport.decode(productImageLoader.load(product), "collage photo"));
            }
        }
        if (photos.isEmpty()) {
            throw new IllegalArgumentException("셀에 등록상품이 없습니다");
        }
        boolean useTemplate = Boolean.TRUE.equals(layout.useTemplate());
        int canvasW = template.getCanvasWidth() == null ? 0 : template.getCanvasWidth();
        int canvasH = template.getCanvasHeight() == null ? 0 : template.getCanvasHeight();
        TemplateElement.Region slot = useTemplate ? slotRegion(template) : null;
        int width = slot != null ? slot.getW() : canvasW;
        int height = slot != null ? slot.getH() : canvasH;
        byte[] collage = collageRenderer.drawPng(layout, photos, width, height);
        List<ImageOp> ops = thumbnailPresetResolver.productImageOps(template);
        byte[] productImage = ops.isEmpty() ? collage
                : imageProcessor.process(imageDecodeSupport.flattenOnWhite(collage), ops);
        if (useTemplate) {
            return thumbnailRenderer.render(template, textBindings, Map.of(PRODUCT_IMAGE_BIND, productImage));
        }
        return thumbnailRenderer.render(wholeCanvas(template, canvasW, canvasH), Map.of(),
                Map.of(PRODUCT_IMAGE_BIND, productImage));
    }

    /** The chosen gallery photos of the layout's cells by id (one query; deleted ids are simply absent). */
    private Map<Long, ProductImage> chosenPhotos(CollageLayout layout) {
        List<Long> ids = layout.cells().stream()
                .map(CollageCell::productImageId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, ProductImage> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            for (ProductImage image : productImageRepository.findAllById(ids)) {
                byId.put(image.getId(), image);
            }
        }
        return byId;
    }

    /** Region of the template's first product-photo slot, or null when there is none / it is incomplete. */
    private static TemplateElement.Region slotRegion(ThumbnailTemplate template) {
        TemplateElement slot = ThumbnailRenderer.firstProductImageElement(template.getElements());
        if (slot == null || slot.getRegion() == null) {
            return null;
        }
        TemplateElement.Region r = slot.getRegion();
        if (r.getW() == null || r.getH() == null || r.getW() <= 0 || r.getH() <= 0) {
            return null;
        }
        return r;
    }

    /** White canvas-sized template with one full-canvas product-photo slot (D10 "the collage is the thumbnail"). */
    private static ThumbnailTemplate wholeCanvas(ThumbnailTemplate template, int width, int height) {
        TemplateElement slot = TemplateElement.builder()
                .type("image")
                .bind(PRODUCT_IMAGE_BIND)
                .region(TemplateElement.Region.builder().x(0).y(0).w(width).h(height).build())
                .build();
        return ThumbnailTemplate.builder()
                .id(template.getId())
                .name(template.getName())
                .canvasWidth(width)
                .canvasHeight(height)
                .backgroundMode(BackgroundMode.WHITE)
                .elements(List.of(slot))
                .fields(List.of())
                .active(Boolean.TRUE)
                .build();
    }
}
