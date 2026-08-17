package com.pms.service;

import com.pms.domain.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;

/**
 * Loads the raw bytes of a product's source image — the base the thumbnail renderer composites
 * ({@code bind:productImage}). The meaning of {@code product.imageUrl} differs by environment
 * (see {@link ImageStorageService}), so this branches on it:
 *
 * <ul>
 *   <li>starts with {@code http} → public URL (S3 on dev/prod): fetch via HTTP GET.</li>
 *   <li>otherwise → disk-relative path (Local on local/test): read via {@code ImageStorageService.getImage}.</li>
 * </ul>
 *
 * <p>Any missing image / network / decode failure surfaces as {@link IllegalArgumentException}
 * (→ 400 "상품 이미지를 불러올 수 없습니다"). File: {@code service/ProductImageLoader.java}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImageLoader {

    private final ImageStorageService imageStorageService;

    public byte[] load(Product product) {
        String url = product == null ? null : product.getImageUrl();
        return loadUrl(url);
    }

    /**
     * Load raw image bytes from a stored image value (public http URL or disk-relative path). Extracted
     * so the master image override ({@code MasterProduct.sourceImageUrl}) can reuse the same http/local
     * branch as {@link #load(Product)} (FEATURE_2608_06 / 3b-2).
     *
     * @param url stored image value (S3 public URL on dev/prod, disk-relative path on local/test)
     * @return the image bytes
     * @throws IllegalArgumentException (→400) if blank / missing / network / read failure
     */
    public byte[] loadUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("상품 이미지를 불러올 수 없습니다: 이미지가 없습니다");
        }
        try {
            if (url.startsWith("http")) {
                URL target = URI.create(url).toURL();
                try (InputStream in = target.openStream()) {
                    return in.readAllBytes();
                }
            }
            return imageStorageService.getImage(url);
        } catch (Exception e) {
            log.warn("Failed to load product image: {}", url, e);
            throw new IllegalArgumentException("상품 이미지를 불러올 수 없습니다", e);
        }
    }
}
