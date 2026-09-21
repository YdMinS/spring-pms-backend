package com.pms.service;

import com.pms.config.ImageStorageProperties;
import com.pms.domain.Product;
import com.pms.domain.ProductImage;
import com.pms.dto.response.ProductImageResponse;
import com.pms.exception.ImageInUseException;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.MasterImageZoneAssignmentRepository;
import com.pms.repository.MasterProductImageRepository;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
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
    // Cross-aggregate refs for the 40 delete-guard + reference-entry cleanup.
    private final MasterImageZoneAssignmentRepository assignmentRepository;
    private final MasterProductImageRepository masterProductImageRepository;
    private final ImageStorageService imageStorageService;
    private final ImageValidator imageValidator;
    // 2609_67: 마켓 URL 에서 바이트를 가져오는 공용 로더. 새 다운로더를 만들지 말 것.
    private final ProductImageLoader productImageLoader;
    // 2609_67: URL 가져오기의 크기 상한 — 폼 업로드(ImageValidator)와 같은 기준을 쓴다.
    private final ImageStorageProperties imageStorageProperties;

    /** 2609_67/D5: 한 번에 가져올 수 있는 장수. {@code loadUrl} 에 시간 제한이 없으므로 노출을 이 상한이 묶는다. */
    private static final int MAX_URLS_PER_REQUEST = 10;
    /** 2609_67/D5: 쿠팡 이미지 CDN. 자기 자신 또는 서브도메인만 통과한다. */
    private static final String COUPANG_IMAGE_HOST = "coupangcdn.com";

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

    /**
     * 2609_67/D5: 마켓 이미지 URL → 우리 저장소 복제. {@link #addImages} 와 <b>같은 흐름</b>이고 파일 출처만
     * 다르다(MultipartFile 대신 http GET).
     *
     * <p>🔴 <b>URL 은 사용자가 보낸 값</b>이라 서버가 그대로 친다 → 가드를 <b>내려받기 전에 전부</b> 통과시킨다:
     * {@code https} · 호스트가 <b>허용 목록</b>에 있음 · 10장 이하. 호스트는 {@code URI.toURL().getHost()} 로
     * 꺼내 비교한다 — {@code contains("coupangcdn.com")} 같은 부분일치는
     * {@code https://evil.com/?x=coupangcdn.com} 을 통과시킨다.</p>
     *
     * <p>🔴 형식은 <b>매직바이트</b>로 정한다(JPEG {@code FF D8 FF} / PNG {@code 89 50 4E 47}) — 마켓이 주는
     * {@code Content-Type} 은 믿지 않는다(로더는 바이트만 준다).
     * ⚠️ {@code ProductImageLoader.loadUrl} 에는 시간 제한이 없다({@code URL.openStream()}) — 여기서 고치지
     * 않는다(공용 로더의 별건). 10장 상한이 그 노출을 묶는다.</p>
     */
    @Override
    @Transactional
    public List<ProductImageResponse> addImagesFromUrls(Long productId, List<String> urls) {
        Product product = requireScopedProduct(productId);
        if (urls == null || urls.isEmpty()) {
            throw new IllegalArgumentException("가져올 이미지가 없습니다");
        }
        if (urls.size() > MAX_URLS_PER_REQUEST) {
            throw new IllegalArgumentException("한 번에 10장까지 가져올 수 있습니다");
        }
        // 🔴 전부 검사한 뒤에 한 장도 내려받는다 — 하나라도 어긋나면 외부 호출 0회로 400 이다.
        List<String> targets = urls.stream().map(this::requireAllowedImageUrl).toList();

        List<ProductImage> existing = imageRepository.findByProductIdOrderBySortOrderAsc(productId);
        // Same rule as addImages: max(sortOrder)+1, never size() (a delete leaves a gap).
        int nextOrder = existing.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1;

        List<ProductImage> toSave = new ArrayList<>();
        long timestamp = System.currentTimeMillis();
        for (int i = 0; i < targets.size(); i++) {
            byte[] bytes = productImageLoader.loadUrl(targets.get(i));
            ImageFormat format = detectImageFormat(bytes);
            if (bytes.length > imageStorageProperties.getMaxFileSize()) {
                throw new IllegalArgumentException("이미지 크기가 허용치를 넘습니다");
            }
            String url = imageStorageService.uploadBytes(bytes, "products",
                    "product_" + productId + "_" + timestamp + "_" + i + "." + format.extension(),
                    format.contentType());
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
    @Transactional
    public List<ProductImageResponse> copyImages(Long productId, List<Long> sourceImageIds) {
        Product target = requireScopedProduct(productId);
        if (sourceImageIds == null || sourceImageIds.isEmpty()) {
            throw new IllegalArgumentException("복제할 이미지가 없습니다");
        }
        List<ProductImage> existing = imageRepository.findByProductIdOrderBySortOrderAsc(productId);
        // Same rule as addImages: max(sortOrder)+1, never size() (a delete leaves a gap).
        int nextOrder = existing.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1;

        List<ProductImage> toSave = new ArrayList<>();
        for (Long sourceImageId : sourceImageIds) {
            // Skip, never throw: a stale clipboard entry (deleted meanwhile) or a cross-tenant id must not
            // block the remaining good sources — and a 404 would also reveal that the id exists elsewhere.
            ProductImage source = imageRepository.findById(sourceImageId).orElse(null);
            if (source == null) {
                continue;
            }
            // findScopedById (not requireScopedProduct): @TenantId only filters query-derived selects, so the
            // inherited findById would happily hand over another tenant's product. Reading the LAZY proxy's id
            // does not initialize it.
            if (productRepository.findScopedById(source.getProduct().getId()).isEmpty()) {
                continue;
            }
            toSave.add(ProductImage.builder()
                    .product(target)
                    .sortOrder(nextOrder++)
                    // Reference copy: the storage object is shared as-is (no imageStorageService.uploadImage).
                    .imageUrl(source.getImageUrl())
                    .build());
        }
        if (toSave.isEmpty()) {
            throw new IllegalArgumentException("붙여넣을 원본 이미지가 없습니다");
        }
        List<ProductImage> saved = imageRepository.saveAll(toSave); // single call

        List<ProductImage> gallery = new ArrayList<>(existing);
        gallery.addAll(saved);
        gallery.sort(Comparator.comparingInt(ProductImage::getSortOrder));
        syncRepresentative(target, gallery);
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
        // Representative first (same reason as deleteImage): if this product's representative still held
        // oldUrl, the guard below would count itself and never free the file.
        syncRepresentative(product, imageRepository.findByProductIdOrderBySortOrderAsc(productId));
        // The row now points at newUrl. Keep the old object while any row or representative still uses it
        // (clipboard reference copy shares urls across products).
        if (imageRepository.countByImageUrl(oldUrl) == 0 && !productRepository.existsByImageUrl(oldUrl)) {
            deleteFromStorage(oldUrl, imageId);
        }
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
        // 40 reference guard: if a master pool reference of this slot is PLACED on a zone or the cover
        // (DRAFT included), deleting the source would break the live-link → 409. The check is only the
        // image↔master mapping existence (no ProductListing publish-state traversal / dangling cleanup).
        if (assignmentRepository.existsByImage_ProductImageId(imageId)) {
            throw new ImageInUseException();
        }
        // Not placed → drop any unmapped reference entries live-linking this slot (palette cleanup), then delete.
        masterProductImageRepository.deleteByProductImageId(imageId);
        imageRepository.delete(image);
        imageRepository.flush(); // so the counts below already see the row gone
        List<ProductImage> remaining = imageRepository.findByProductIdOrderBySortOrderAsc(productId);
        // ⚠️ Representative sync must run BEFORE the guard: if the deleted image was this product's
        // representative, existsByImageUrl would count this very product and the file would never be freed.
        syncRepresentative(product, remaining);
        String url = image.getImageUrl();
        // Physical delete is conditional on both the last-image rule (the representative still points here,
        // empty-gallery rule keeps it) and the sharing guard — another row or another product's
        // representative may use the same storage object (clipboard reference copy, FEATURE_2609_62).
        if (!remaining.isEmpty()
                && imageRepository.countByImageUrl(url) == 0
                && !productRepository.existsByImageUrl(url)) {
            deleteFromStorage(url, imageId);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 2609_67/D5 SSRF 가드(2609_68 에서 허용 호스트 확장): {@code https} + <b>허용된 두 호스트</b>만 통과한다.
     *
     * <p>🔴 호스트를 <b>꺼내서</b> 비교한다 — {@code contains} 부분일치는
     * {@code https://evil.com/?x=coupangcdn.com} 을 통과시킨다.</p>
     *
     * <p><b>허용 호스트는 둘이다</b>. 마켓이 돌려주는 사진 주소에는 쿠팡이 가진 사진과
     * <b>우리가 올려서 쿠팡에 보낸 사진</b>이 섞여 있기 때문이다 — 대표 사진의 {@code vendorPath} 와
     * 우리가 만든 상세 HTML 의 {@code <img src>} 가 우리 저장소를 가리킨다. 쿠팡 CDN 만 허용하면
     * <b>화면에는 보이는데 가져올 수는 없는 사진</b>이 생긴다(2026-09-21 실제 발생).</p>
     * <ul>
     *   <li>{@code coupangcdn.com} — 자기 자신 또는 <b>서브도메인</b>({@code image1.}·{@code thumbnail6.} …)</li>
     *   <li>우리 저장소 호스트({@link ImageStorageProperties#resolvePublicImageHost()}) — <b>완전일치만</b>.
     *       ⚠️ 여기에 서브도메인 접미사 매칭을 쓰면 {@code *.amazonaws.com} 아무 버킷이나 통과한다.</li>
     * </ul>
     *
     * <p>⚠️ 저장소가 {@code local} 이면 우리 호스트는 {@code null} 이라 쿠팡 CDN 만 남는다(공개 URL 이 없다).</p>
     *
     * @return 실제로 내려받을 주소(= 검사를 통과한 {@code https} URL). 호출부는 <b>이 값</b>으로 가져온다.
     */
    private String requireAllowedImageUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("허용되지 않은 이미지 주소입니다");
        }
        // 🔴 쿠팡이 주는 사진 주소에는 http 가 섞여 있다(어댑터가 절대 URL 을 그대로 넘긴다). 브라우저는
        //    https 화면에서 그런 이미지를 알아서 https 로 올려 보여주므로 사용자 눈에는 멀쩡한 사진인데,
        //    우리가 받는 문자열은 http 라 스킴만 보고 막으면 "보이는데 못 가져오는" 사진이 된다.
        //    ⚠️ 승격은 허용 호스트 판정 **앞**에 오지만 호스트를 넓히지는 않는다 — 우리가 실제로 치는 주소는
        //    언제나 https 다(평문 요청을 대신 보내주는 것이 아니라, 보내지 않는다).
        String target = url.startsWith("http://") ? "https://" + url.substring("http://".length()) : url;
        if (!target.startsWith("https://")) {
            throw new IllegalArgumentException("허용되지 않은 이미지 주소입니다");
        }
        String host;
        try {
            host = URI.create(target).toURL().getHost();
        } catch (Exception e) {
            throw new IllegalArgumentException("허용되지 않은 이미지 주소입니다");
        }
        if (host == null) {
            throw new IllegalArgumentException("허용되지 않은 이미지 주소입니다");
        }
        if (isCoupangCdn(host) || isOwnStorage(host)) {
            return target;
        }
        // 어느 호스트가 막혔는지 로그에 남긴다 — 화면 문구만으로는 원인을 좁힐 수 없다.
        log.warn("가져오기가 막힌 이미지 주소 — host={}, url={}", host, url);
        throw new IllegalArgumentException("허용되지 않은 이미지 주소입니다: " + host);
    }

    /** 쿠팡 이미지 CDN 자신 또는 그 서브도메인. */
    private static boolean isCoupangCdn(String host) {
        String lower = host.toLowerCase();
        return lower.equals(COUPANG_IMAGE_HOST) || lower.endsWith("." + COUPANG_IMAGE_HOST);
    }

    /** 우리 저장소가 사진을 내주는 호스트. 🔴 완전일치만 — 접미사 매칭은 남의 버킷까지 연다. */
    private boolean isOwnStorage(String host) {
        String ownHost = imageStorageProperties.resolvePublicImageHost();
        return ownHost != null && !ownHost.isBlank() && host.equalsIgnoreCase(ownHost);
    }

    /**
     * 2609_67/D5: 바이트 앞부분(매직바이트)으로 형식을 정한다 — 마켓 응답 헤더를 믿지 않는다.
     * JPEG {@code FF D8 FF} · PNG {@code 89 50 4E 47} 외에는 400.
     */
    private static ImageFormat detectImageFormat(byte[] bytes) {
        if (bytes != null && bytes.length >= 3
                && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
            return new ImageFormat("image/jpeg", "jpg");
        }
        if (bytes != null && bytes.length >= 4
                && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return new ImageFormat("image/png", "png");
        }
        throw new IllegalArgumentException("이미지 파일이 아닙니다");
    }

    /** 매직바이트로 판정한 형식(저장 시 contentType + 파일 확장자). */
    private record ImageFormat(String contentType, String extension) {}

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
