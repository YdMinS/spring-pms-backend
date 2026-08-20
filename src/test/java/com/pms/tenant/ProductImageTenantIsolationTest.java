package com.pms.tenant;

import com.pms.domain.Product;
import com.pms.domain.ProductImage;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProductImageRepository;
import com.pms.repository.ProductRepository;
import com.pms.repository.RefreshTokenRepository;
import com.pms.security.TenantContext;
import com.pms.service.ProductImageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves tenant isolation for the {@link ProductImage} gallery (39). The child has no {@code @TenantId};
 * isolation is enforced by resolving the parent {@link Product} via {@code findScopedById} (Hibernate
 * {@code @TenantId} filter → cross-tenant id yields empty → 404).
 *
 * <p>⚠️ Intentionally NOT {@code @Transactional} — Hibernate resolves the tenant once per session, so each
 * repository/service call must open its own session to read the live {@link TenantContext} (same pattern as
 * {@code ProductTenantIsolationTest}). Cleanup uses native SQL (bypasses the tenant filter).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisplayName("ProductImage tenant isolation (parent @TenantId)")
class ProductImageTenantIsolationTest {

    private static final Long TENANT_1 = 1L;
    private static final Long TENANT_2 = 2L;

    @Autowired private ProductImageService productImageService;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductImageRepository imageRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        refreshTokenRepository.deleteAll();
        jdbcTemplate.execute("delete from product_image"); // FK → products
        jdbcTemplate.execute("delete from products");
        jdbcTemplate.execute("delete from member");
    }

    /** Seed a product + one gallery image under tenant 1; return the product id. */
    private Long seedTenant1ProductWithImage() {
        TenantContext.set(TENANT_1);
        Product product = productRepository.save(
                Product.builder().productName("A").active(true).build());
        imageRepository.save(ProductImage.builder()
                .product(product).sortOrder(0).imageUrl("u0").build());
        return product.getId();
    }

    @Test
    @DisplayName("tenant 1 owner can read its own gallery (positive control)")
    void owner_canList() {
        Long productId = seedTenant1ProductWithImage();
        TenantContext.set(TENANT_1);
        assertThat(productImageService.list(productId)).hasSize(1);
    }

    @Test
    @DisplayName("tenant 2 gets 404 on list / add / delete of tenant 1's product images")
    void crossTenant_gets404() {
        Long productId = seedTenant1ProductWithImage();
        Long imageId = imageRepository.findByProductIdOrderBySortOrderAsc(productId).get(0).getId();

        TenantContext.set(TENANT_2);
        MultipartFile file = new MockMultipartFile("files", "p.png", "image/png", new byte[]{1});

        // Product findScopedById filtered by tenant 2 → empty → 404 (no cross-tenant leak).
        assertThatThrownBy(() -> productImageService.list(productId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> productImageService.addImages(productId, List.of(file)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> productImageService.deleteImage(productId, imageId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
