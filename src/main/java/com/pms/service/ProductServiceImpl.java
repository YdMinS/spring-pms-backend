package com.pms.service;

import com.pms.domain.Product;
import com.pms.dto.request.CreateProductRequest;
import com.pms.dto.request.UpdateProductRequest;
import com.pms.dto.response.ProductResponse;
import com.pms.exception.ResourceNotFoundException;
import com.pms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * ProductServiceImpl - Product service implementation
 *
 * Phase 2-1 TDD CREATE: Implements only create() and supporting helper methods
 * - create(CreateProductRequest request): Creates new product
 * - validatePrice(BigDecimal price): Validates price > 0
 * - validateUnit(String unit): Validates unit in [KG, G, L, ML]
 * - mapToResponse(Product product): Maps Product entity to ProductResponse
 *
 * Other CRUD methods (getProduct, getAllProducts, updateProduct, deleteProduct)
 * will be added in subsequent phases (2-2, 2-3, 2-4, 2-5) following TDD pattern
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private static final String[] VALID_UNITS = {"KG", "G", "L", "ML"};
    private static final int DEFAULT_PAGE_SIZE = 20;

    @Override
    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        // Validate request
        validatePrice(request.getPrice());
        validateUnit(request.getUnit());

        // Build product using immutable pattern
        Product product = Product.builder()
                .barcodeId(request.getBarcodeId())
                .brand(request.getBrand())
                .price(request.getPrice())
                .productName(request.getProductName())
                .store(request.getStore())
                .unit(request.getUnit())
                .volumeHeight(request.getVolumeHeight())
                .volumeLong(request.getVolumeLong())
                .volumeShort(request.getVolumeShort())
                .weight(request.getWeight())
                .description(request.getDescription())
                .name(request.getName())
                .active(true)
                .build();

        Product saved = productRepository.save(product);
        return mapToResponse(saved);
    }

    @Override
    public ProductResponse getProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        if (!product.getActive()) {
            throw new ResourceNotFoundException("Product", id);
        }

        return mapToResponse(product);
    }

    @Override
    public Page<ProductResponse> getAllProducts(int page, int size, String search) {
        // Validate and adjust page size
        if (size <= 0) {
            size = DEFAULT_PAGE_SIZE;
        }

        // Create pageable with createdDate DESC sorting
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        // Fetch products
        Page<Product> productPage;
        if (search == null || search.trim().isEmpty()) {
            productPage = productRepository.findByActiveTrue(pageable);
        } else {
            productPage = productRepository.searchByKeyword(search.trim(), pageable);
        }

        // Convert to response
        return productPage.map(this::mapToResponse);
    }

    @Override
    @Transactional
    public ProductResponse updateProduct(Long id, UpdateProductRequest request) {
        // Find product by id
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        // Check if product is active
        if (!product.getActive()) {
            throw new ResourceNotFoundException("Product", id);
        }

        // Validate updates
        request.getPrice().ifPresent(this::validatePrice);
        request.getUnit().ifPresent(this::validateUnit);

        // Build updated product using immutable pattern - use toBuilder to preserve audit fields
        Product updated = product.toBuilder()
                .barcodeId(request.getBarcodeId().orElse(product.getBarcodeId()))
                .brand(request.getBrand().orElse(product.getBrand()))
                .price(request.getPrice().orElse(product.getPrice()))
                .productName(request.getProductName().orElse(product.getProductName()))
                .store(request.getStore().orElse(product.getStore()))
                .unit(request.getUnit().orElse(product.getUnit()))
                .volumeHeight(request.getVolumeHeight().orElse(product.getVolumeHeight()))
                .volumeLong(request.getVolumeLong().orElse(product.getVolumeLong()))
                .volumeShort(request.getVolumeShort().orElse(product.getVolumeShort()))
                .weight(request.getWeight().orElse(product.getWeight()))
                .description(request.getDescription().orElse(product.getDescription()))
                .name(request.getName().orElse(product.getName()))
                .build();

        // Save updated product
        Product saved = productRepository.save(updated);
        return mapToResponse(saved);
    }

    /**
     * Validate that price is positive (> 0)
     *
     * @param price the price to validate
     * @throws IllegalArgumentException if price <= 0
     */
    private void validatePrice(BigDecimal price) {
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
    }

    /**
     * Validate that unit is one of the allowed units: KG, G, L, ML
     *
     * @param unit the unit to validate
     * @throws IllegalArgumentException if unit is not valid
     */
    private void validateUnit(String unit) {
        boolean isValid = false;
        for (String validUnit : VALID_UNITS) {
            if (validUnit.equals(unit)) {
                isValid = true;
                break;
            }
        }
        if (!isValid) {
            throw new IllegalArgumentException("Unit must be one of: KG, G, L, ML");
        }
    }

    /**
     * Map Product entity to ProductResponse DTO
     *
     * @param product the product entity to map
     * @return ProductResponse DTO
     */
    private ProductResponse mapToResponse(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .barcodeId(product.getBarcodeId())
                .brand(product.getBrand())
                .price(product.getPrice())
                .productName(product.getProductName())
                .store(product.getStore())
                .unit(product.getUnit())
                .volumeHeight(product.getVolumeHeight())
                .volumeLong(product.getVolumeLong())
                .volumeShort(product.getVolumeShort())
                .weight(product.getWeight())
                .description(product.getDescription())
                .name(product.getName())
                .imageUrl(product.getImageUrl())
                .active(product.getActive())
                .createdDate(product.getCreatedAt())
                .modifiedDate(product.getUpdatedAt())
                .build();
    }

    @Override
    @Transactional
    public void deleteProduct(Long id) {
        // Find product by ID
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        // Check if product is active (already soft-deleted products cannot be deleted again)
        if (!product.getActive()) {
            throw new ResourceNotFoundException("Product", id);
        }

        // Soft delete using immutable pattern with Builder - use toBuilder to preserve audit fields
        Product deletedProduct = product.toBuilder()
                .active(false)
                .build();

        // Save updated product
        productRepository.save(deletedProduct);
    }
}
