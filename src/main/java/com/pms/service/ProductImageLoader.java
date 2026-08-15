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
