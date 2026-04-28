package com.pms.service;

import com.pms.dto.request.CreateProductRequest;
import com.pms.dto.response.ProductResponse;

/**
 * ProductService - Service interface for Product management
 *
 * Phase 2-1 TDD CREATE: Only create() method
 * Other methods will be added in future phases:
 * - Phase 2-2: getProduct(Long id)
 * - Phase 2-3: getAllProducts(int page, int size, String search)
 * - Phase 2-4: updateProduct(Long id, UpdateProductRequest request)
 * - Phase 2-5: deleteProduct(Long id)
 */
public interface ProductService {
    ProductResponse create(CreateProductRequest request);
}
