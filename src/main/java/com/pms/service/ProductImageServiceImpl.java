package com.pms.service;

import com.pms.domain.Product;
import com.pms.domain.ProductImage;
import com.pms.dto.response.ProductImageResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link ProductImageService} (FEATURE_2608_06 / 39).
 *
 * <p>Ownership is enforced with {@code ProductRepository.findScopedById} (tenant-filtered → cross-tenant/absent
 * gives 404). {@link ProductImage} has no {@code @TenantId}, so the builder never sets a tenant (isolation
 * flows through the parent product).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductImageServiceImpl implements ProductImageService {

    private final ProductRepository productRepository;
    private final ProductImageRepository imageRepository;
    private final ImageStorageService imageStorageService;
    private final ImageValidator imageValidator;

    @Override
    @Transactional
    public List<ProductImageResponse> addImages(Long productId, List<MultipartFile> files) {
        Product product = requireScopedProduct(productId);
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("업로드할 이미지가 없습니다");
        }
        List<ProductImage> existing = imageRepository.findByProductIdOrderBySortOrderAsc(productId);
        // Next position = max(sortOrder)+1. Do NOT use size() — a delete leaves a gap and size() would
        // collide with an existing sortOrder.
        int nextOrder = existing.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1;

        List<ProductImage> toSave = new ArrayList<>();
        for (MultipartFile file : files) {
            imageValidator.validate(file);
            String url = imageStorageService.uploadImage(file, productId);
            toSave.add(ProductImage.builder()
                    .product(product)
                    .sortOrder(nextOrder++)
                    .imageUrl(url)
                    .build());
        }
        List<ProductImage> saved = imageRepository.saveAll(toSave); // single call

        List<ProductImage> gallery = new ArrayList<>(existing);
        gallery.addAll(saved);
        gallery.sort(Comparator.comparingInt(ProductImage::getSortOrder));
        syncRepresentative(product, gallery);
        return gallery.stream().map(ProductImageResponse::from).toList();
    }

    @Override
    public List<ProductImageResponse> list(Long productId) {
        requireScopedProduct(productId);
        return imageRepository.findByProductIdOrderBySortOrderAsc(productId).stream()
                .map(ProductImageResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public ProductImageResponse replaceImage(Long productId, Long imageId, MultipartFile file) {
        Product product = requireScopedProduct(productId);
        ProductImage image = requireOwnedImage(productId, imageId);
        imageValidator.validate(file);
        String oldUrl = image.getImageUrl();
        String newUrl = imageStorageService.uploadImage(file, productId);
        // Update-in-place: keep the same ProductImage.id (a master 40-reference must not dangle).
        ProductImage updated = imageRepository.save(image.toBuilder().imageUrl(newUrl).build());
        deleteFromStorage(oldUrl, imageId);
        syncRepresentative(product, imageRepository.findByProductIdOrderBySortOrderAsc(productId));
        return ProductImageResponse.from(updated);
    }

    @Override
    @Transactional
    public List<ProductImageResponse> reorder(Long productId, List<Long> imageIds) {
        Product product = requireScopedProduct(productId);
        List<ProductImage> gallery = imageRepository.findByProductIdOrderBySortOrderAsc(productId);
        // Exact set match (size pre-check catches duplicates; set match catches missing/foreign ids).
        Map<Long, ProductImage> byId = new HashMap<>();
        gallery.forEach(img -> byId.put(img.getId(), img));
        if (imageIds.size() != gallery.size() || !byId.keySet().equals(new HashSet<>(imageIds))) {
            throw new IllegalArgumentException("이미지 목록 불일치");
        }
        List<ProductImage> reordered = new ArrayList<>();
        for (int i = 0; i < imageIds.size(); i++) {
            reordered.add(byId.get(imageIds.get(i)).toBuilder().sortOrder(i).build());
        }
        List<ProductImage> saved = imageRepository.saveAll(reordered); // already in new 0..n order
        syncRepresentative(product, saved);
        return saved.stream().map(ProductImageResponse::from).toList();
    }

    @Override
    @Transactional
    public void deleteImage(Long productId, Long imageId) {
        Product product = requireScopedProduct(productId);
        ProductImage image = requireOwnedImage(productId, imageId);
        // ⚠️ The 40 reference delete-guard will be added to this method (currently no referrers → simple delete).
        imageRepository.delete(image);
        List<ProductImage> remaining = imageRepository.findByProductIdOrderBySortOrderAsc(productId);
        // Physical delete is conditional: skip when this was the last image, because the representative
        // still points at this URL (empty-gallery rule below keeps it) — deleting the object would dangle it.
        if (!remaining.isEmpty()) {
            deleteFromStorage(image.getImageUrl(), imageId);
        }
        syncRepresentative(product, remaining);
    }

    // ---------------------------------------------------------------- helpers

    /** Tenant-scoped fetch; a cross-tenant/absent id yields 404 (findScopedById is @TenantId-filtered). */
    private Product requireScopedProduct(Long productId) {
        return productRepository.findScopedById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    }

    /** The image must belong to the product, else 404. */
    private ProductImage requireOwnedImage(Long productId, Long imageId) {
        return imageRepository.findById(imageId)
                .filter(img -> img.getProduct().getId().equals(productId))
                .orElseThrow(() -> new ResourceNotFoundException("ProductImage", imageId));
    }

    /**
     * Representative (SSOT of the §1-3 sync rule): after any gallery mutation, {@code product.imageUrl} =
     * the first gallery image's url. <b>Empty-gallery rule</b>: if the gallery is now empty, keep the
     * previous value (do NOT blank it) — the live consumers ({@code ProductImageLoader} / thumbnail base)
     * must not see null. Tradeoff: because the representative is kept, the last image's storage object is
     * intentionally NOT physically deleted (see {@code deleteImage}), so the link stays alive.
     */
    private void syncRepresentative(Product product, List<ProductImage> gallery) {
        if (gallery.isEmpty()) {
            return; // keep previous representative
        }
        productRepository.save(product.toBuilder()
                .imageUrl(gallery.get(0).getImageUrl())
                .build());
    }

    /** Best-effort storage delete (DB cleanup still commits if this fails). */
    private void deleteFromStorage(String imageUrl, Long imageId) {
        try {
            imageStorageService.deleteImage(imageUrl);
        } catch (Exception e) {
            log.warn("Failed to delete product image from storage (id={}): {}", imageId, e.getMessage());
        }
    }
}
