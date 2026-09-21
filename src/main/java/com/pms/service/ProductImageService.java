package com.pms.service;

import com.pms.dto.response.ProductImageResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Product image gallery (1:N) service (FEATURE_2608_06 / 39).
 *
 * <p>Every method resolves the tenant-scoped product first (404 if absent/cross-tenant) and, after any
 * mutation, re-syncs the representative {@code Product.imageUrl} to the gallery's first image so the live
 * consumers ({@code ProductImageLoader}, thumbnail base) keep working. Ownership + isolation flow through
 * the parent {@link com.pms.domain.Product} ({@code ProductImage} has no {@code @TenantId}).</p>
 */
public interface ProductImageService {

    /** Append the uploaded files to the product's gallery; returns the full gallery in order. */
    List<ProductImageResponse> addImages(Long productId, List<MultipartFile> files);

    /**
     * Copy other products' gallery images into this product by <b>reference</b> (FEATURE_2609_62): a new row
     * per source id sharing the source {@code imageUrl} — no file is re-uploaded. Sources that no longer
     * exist or belong to another tenant are skipped silently (a 404 would block the remaining good ones);
     * an all-skipped/empty request is a 400. Returns the full gallery in order.
     */
    List<ProductImageResponse> copyImages(Long productId, List<Long> sourceImageIds);

    /**
     * 마켓 이미지 URL 을 서버가 내려받아 우리 저장소에 넣고 갤러리에 덧붙인다(2609_67/D5).
     * 🔴 URL 을 그대로 저장하지 않는다 — 마켓에서 사진이 바뀌거나 내려가면 우리 물품 사진이 깨지고,
     * 썸네일 생성기가 그 바이트를 매번 마켓에서 읽게 된다.
     *
     * <p>🔴 사용자가 보낸 URL 을 서버가 그대로 친다(SSRF) → {@code https} + <b>마켓 이미지 호스트</b>
     * ({@code *.coupangcdn.com})만 허용하고, 한 번에 <b>10장까지</b>다. 하나라도 어긋나면 아무것도 내려받지
     * 않고 400 이다. 형식은 응답 헤더가 아니라 <b>매직바이트</b>로 정한다(JPEG/PNG 만).</p>
     *
     * @return 덧붙인 뒤의 전체 갤러리(순서대로)
     */
    List<ProductImageResponse> addImagesFromUrls(Long productId, List<String> urls);

    /** The product's gallery in order. */
    List<ProductImageResponse> list(Long productId);

    /** Replace one image in place (same {@code ProductImage.id}) with a new upload. */
    ProductImageResponse replaceImage(Long productId, Long imageId, MultipartFile file);

    /** Reorder the gallery to exactly {@code imageIds} (set must match the current gallery). */
    List<ProductImageResponse> reorder(Long productId, List<Long> imageIds);

    /** Remove one image from the gallery. */
    void deleteImage(Long productId, Long imageId);
}
