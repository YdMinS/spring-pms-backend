package com.pms.service;

import com.pms.domain.Product;
import com.pms.domain.ProductThumbnail;
import com.pms.domain.Seller;
import com.pms.domain.ThumbnailTemplate;
import com.pms.dto.response.ProductThumbnailResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProductRepository;
import com.pms.repository.ProductThumbnailRepository;
import com.pms.repository.SellerRepository;
import com.pms.repository.ThumbnailTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-product-per-seller thumbnail service. Tenant isolation is automatic via {@code @TenantId} on
 * {@link ProductThumbnail} / {@link ThumbnailTemplate} — no manual tenant conditions here.
 *
 * <p>Generation binds {@code brandName}/{@code productName} (text) and {@code productImage} (bytes,
 * always loaded via {@link ProductImageLoader}) then renders through {@link ThumbnailRenderer}.
 * Regeneration and override both <b>upsert</b> the {@code (productId, sellerId)} row (never a second
 * insert), best-effort deleting the superseded storage object.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductThumbnailServiceImpl implements ProductThumbnailService {

    private static final String STORAGE_CATEGORY = "thumbnails";

    private final ProductRepository productRepository;
    private final SellerRepository sellerRepository;
    private final ThumbnailTemplateRepository templateRepository;
    private final ProductThumbnailRepository thumbnailRepository;
    private final ThumbnailRenderer thumbnailRenderer;
    private final ProductImageLoader productImageLoader;
    private final ImageStorageService imageStorageService;
    private final ImageValidator imageValidator;

    @Override
    @Transactional
    public ProductThumbnailResponse generate(Long productId, Long sellerId) {
        Product product = findProduct(productId);
        Seller seller = findSeller(sellerId);
        ThumbnailTemplate template = resolveTemplate(sellerId);

        Map<String, String> textBindings = new HashMap<>();
        textBindings.put("brandName", product.getBrand());
        textBindings.put("productName", product.getProductName());
        Map<String, byte[]> imageBindings = Map.of("productImage", productImageLoader.load(product));

        byte[] jpeg = thumbnailRenderer.render(template, textBindings, imageBindings);
        String url = imageStorageService.uploadBytes(
                jpeg, STORAGE_CATEGORY, filename(productId, sellerId, "jpg"), "image/jpeg");

        ProductThumbnail saved = upsert(productId, sellerId, url,
                template.getId(), ProductThumbnail.Source.GENERATED);
        return toResponse(saved, seller.getSellerName());
    }

    @Override
    @Transactional
    public ProductThumbnailResponse override(Long productId, Long sellerId, MultipartFile file) {
        Product ignored = findProduct(productId); // 404 if product absent
        Seller seller = findSeller(sellerId);
        imageValidator.validate(file);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("업로드 파일을 읽을 수 없습니다", e);
        }
        String url = imageStorageService.uploadBytes(
                bytes, STORAGE_CATEGORY, filename(productId, sellerId, "jpg"), file.getContentType());

        ProductThumbnail saved = upsert(productId, sellerId, url,
                null, ProductThumbnail.Source.MANUAL_OVERRIDE);
        return toResponse(saved, seller.getSellerName());
    }

    @Override
    @Transactional
    public void delete(Long productId, Long sellerId) {
        ProductThumbnail existing = thumbnailRepository.findByProductIdAndSellerId(productId, sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductThumbnail", productId));
        thumbnailRepository.delete(existing);
        bestEffortDeleteStorage(existing.getImageUrl());
    }

    @Override
    public List<ProductThumbnailResponse> listByProduct(Long productId) {
        List<ProductThumbnail> thumbnails = thumbnailRepository.findByProductId(productId);
        if (thumbnails.isEmpty()) {
            return List.of();
        }
        Set<Long> sellerIds = thumbnails.stream()
                .map(ProductThumbnail::getSellerId)
                .collect(Collectors.toSet());
        Map<Long, String> sellerNames = sellerRepository.findAllById(sellerIds).stream()
                .collect(Collectors.toMap(Seller::getId, Seller::getSellerName));
        return thumbnails.stream()
                .map(t -> toResponse(t, sellerNames.get(t.getSellerId())))
                .toList();
    }

    /**
     * The tenant's active default template. The {@code sellerId} argument is currently ignored (all
     * sellers share the default); the signature is kept for the later per-seller assignment step.
     */
    private ThumbnailTemplate resolveTemplate(Long sellerId) {
        return templateRepository.findByIsDefaultTrueAndActiveTrue()
                .orElseThrow(() -> new IllegalArgumentException("기본 템플릿이 없습니다"));
    }

    /** Upsert on (productId, sellerId): rebuild existing row (same id) or insert a new one. */
    private ProductThumbnail upsert(Long productId, Long sellerId, String url,
                                    Long templateId, ProductThumbnail.Source source) {
        LocalDateTime now = LocalDateTime.now();
        ProductThumbnail existing = thumbnailRepository
                .findByProductIdAndSellerId(productId, sellerId).orElse(null);

        ProductThumbnail toSave;
        if (existing != null) {
            toSave = existing.toBuilder()
                    .imageUrl(url)
                    .templateId(templateId)
                    .source(source)
                    .generatedAt(now)
                    .build();
        } else {
            toSave = ProductThumbnail.builder()
                    .productId(productId)
                    .sellerId(sellerId)
                    .imageUrl(url)
                    .templateId(templateId)
                    .source(source)
                    .generatedAt(now)
                    .build();
        }
        ProductThumbnail saved = thumbnailRepository.save(toSave);
        // Best-effort: drop the superseded storage object after a successful upsert.
        if (existing != null && existing.getImageUrl() != null
                && !existing.getImageUrl().equals(url)) {
            bestEffortDeleteStorage(existing.getImageUrl());
        }
        return saved;
    }

    /**
     * Best-effort storage delete — never rolls back the transaction. {@code deleteImage} is graceful and
     * branches on path vs URL, so passing the stored {@code imageUrl} is compatible with Local and S3.
     */
    private void bestEffortDeleteStorage(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return;
        }
        try {
            imageStorageService.deleteImage(imageUrl);
        } catch (Exception e) {
            log.warn("Best-effort thumbnail storage delete failed: {}", imageUrl, e);
        }
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    }

    private Seller findSeller(Long sellerId) {
        return sellerRepository.findById(sellerId)
                .orElseThrow(() -> new ResourceNotFoundException("Seller", sellerId));
    }

    private static String filename(Long productId, Long sellerId, String ext) {
        return "thumb_" + productId + "_" + sellerId + "_" + System.currentTimeMillis() + "." + ext;
    }

    private ProductThumbnailResponse toResponse(ProductThumbnail t, String sellerName) {
        return ProductThumbnailResponse.builder()
                .id(t.getId())
                .productId(t.getProductId())
                .sellerId(t.getSellerId())
                .sellerName(sellerName)
                .templateId(t.getTemplateId())
                .imageUrl(t.getImageUrl())
                .source(t.getSource())
                .generatedAt(t.getGeneratedAt())
                .build();
    }
}
